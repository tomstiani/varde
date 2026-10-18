package no.varde.app.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import no.varde.app.inbox.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val alertId = "alert_" + "b".repeat(32)

    @Test fun announcesSourceBeforeFetchingAndEnrichesWithoutAnotherInterruption() = runBlocking {
        val connection = Connection.create("Home", "https://home.example", "secret", "home")
        val settings = Settings(listOf(connection))
        val response = CompletableDeferred<AlertDetail>()
        val backend = Reader { response.await() }
        val output = Output()
        val coordinator = NotificationCoordinator(settings, backend, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        val delivery = async { coordinator.receive(AlertSignal("1", "a".repeat(32), alertId)) }
        withTimeout(5_000) { while (output.shown.isEmpty()) delay(5) }
        assertEquals(1, output.shown.size)
        assertEquals("Home", output.shown.single().first.source)
        assertNull(output.shown.single().first.detail)
        assertTrue(output.shown.single().second)
        response.complete(detail(alertId))
        delivery.await()
        assertEquals("Original summary", output.shown.last().first.detail?.summary)
        assertFalse(output.shown.last().second)
        assertEquals(output.shown.first().first.targetId, output.shown.last().first.targetId)
    }

    @Test fun duplicateSignalAfterRestartDoesNotInterruptAgain() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val output = Output()
        val directory = temporary.newFolder()
        val backend = Reader { detail(alertId) }
        val first = NotificationCoordinator(settings, backend, directory, output)
        first.activate("home", "a".repeat(32))
        val signal = AlertSignal("1", "a".repeat(32), alertId)
        first.receive(signal)
        first.receive(signal)
        val beforeRestart = output.shown.size
        NotificationCoordinator(settings, backend, directory, output).receive(signal)
        assertTrue(output.shown.size > beforeRestart)
        assertEquals(1, output.shown.count { it.second })
        assertEquals(1, output.shown.map { it.first.targetId }.distinct().size)
    }

    @Test fun rejectsMalformedAndObsoleteSignalsWithoutFetching() = runBlocking {
        val home = Connection.create("Home", "https://home.example", "secret", "home")
        val settings = Settings(listOf(home))
        val output = Output()
        val coordinator = NotificationCoordinator(settings, Reader { error("Unexpected network request") }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        coordinator.receive(AlertSignal("2", "a".repeat(32), "alert_" + "b".repeat(32)))
        coordinator.receive(AlertSignal("1", "unknown", "alert_" + "b".repeat(32)))
        coordinator.receive(AlertSignal("1", "a".repeat(32), "../other"))
        settings.connections = listOf(Connection.create("Home", "https://other.example", "new-secret", "home"))
        coordinator.receive(AlertSignal("1", "a".repeat(32), "alert_" + "b".repeat(32)))
        settings.connections = listOf(home)
        coordinator.receive(AlertSignal("1", "a".repeat(32), "alert_" + "b".repeat(32)))
        assertTrue(output.shown.isEmpty())
    }

    @Test fun failedReadRetainsGenericAndRetryEnrichesSilently() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val output = Output()
        var failing = true
        val coordinator = NotificationCoordinator(settings, Reader {
            if (failing) throw InboxFailure(Problem.NETWORK)
            detail(alertId)
        }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        val signal = AlertSignal("1", "a".repeat(32), alertId)
        coordinator.receive(signal)
        assertEquals(1, output.shown.size)
        assertNull(output.shown.single().first.detail)
        failing = false
        coordinator.receive(signal)
        assertEquals("Original summary", output.shown.last().first.detail?.summary)
        assertEquals(1, output.shown.count { it.second })
    }

    @Test fun dismissalDuringFetchSurvivesRestartAndNeverResurrects() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val response = CompletableDeferred<AlertDetail>()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(settings, Reader { response.await() }, directory, output)
        coordinator.activate("home", "a".repeat(32))
        val signal = AlertSignal("1", "a".repeat(32), alertId)
        val delivery = async { coordinator.receive(signal) }
        withTimeout(5_000) { while (output.shown.isEmpty()) delay(5) }
        coordinator.dismiss(output.shown.single().first.targetId)
        response.complete(detail(alertId))
        delivery.await()
        NotificationCoordinator(settings, Reader { error("Dismissed alert must not be fetched") }, directory, output).receive(signal)
        assertEquals(1, output.shown.size)
        assertFalse(output.active(output.shown.single().first.targetId))
    }

    @Test fun replacedAccessDuringFetchCancelsOldNotificationAndTap() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val response = CompletableDeferred<AlertDetail>()
        val output = Output()
        val coordinator = NotificationCoordinator(settings, Reader { response.await() }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        val delivery = async { coordinator.receive(AlertSignal("1", "a".repeat(32), alertId)) }
        withTimeout(5_000) { while (output.shown.isEmpty()) delay(5) }
        val target = output.shown.single().first.targetId
        settings.connections = listOf(Connection.create("Home", "https://other.example", "replacement", "home"))
        response.complete(detail(alertId))
        delivery.await()
        assertEquals(1, output.shown.size)
        assertFalse(output.active(target))
        assertNull(coordinator.resolveTap(target))
    }

    @Test fun deniedPermissionNeverPostsAndDoesNotCreateAnAudibleBacklog() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val output = Output().apply { allowed = false }
        val coordinator = NotificationCoordinator(settings, Reader { error("Denied notification must not fetch") }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        val signal = AlertSignal("1", "a".repeat(32), alertId)
        coordinator.receive(signal)
        output.allowed = true
        coordinator.receive(signal)
        assertTrue(output.shown.isEmpty())
    }

    @Test fun renamingPreservesContentAndRemovalCancelsTheOldSource() = runBlocking {
        val home = Connection.create("Home", "https://home.example", "secret", "home")
        val settings = Settings(listOf(home))
        val output = Output()
        val coordinator = NotificationCoordinator(settings, Reader { detail(alertId) }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        coordinator.receive(AlertSignal("1", "a".repeat(32), alertId))
        settings.connections = listOf(Connection.create("Renamed", "https://home.example", "secret", "home"))
        coordinator.synchronize()
        assertEquals("Renamed", output.shown.last().first.source)
        assertEquals("Original summary", output.shown.last().first.detail?.summary)
        assertFalse(output.shown.last().second)
        val target = output.shown.last().first.targetId
        settings.connections = emptyList()
        coordinator.synchronize()
        assertFalse(output.active(target))
        assertNull(coordinator.resolveTap(target))
    }

    @Test fun replacingOneSessionCannotReviveItOrCollideWithAnotherSource() = runBlocking {
        val settings = Settings(listOf(
            Connection.create("Home", "https://home.example", "secret", "home"),
            Connection.create("Cabin", "https://cabin.example", "other", "cabin"),
        ))
        val output = Output()
        val coordinator = NotificationCoordinator(settings, Reader { detail(alertId) }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        coordinator.activate("cabin", "c".repeat(32))
        coordinator.receive(AlertSignal("1", "a".repeat(32), alertId))
        coordinator.receive(AlertSignal("1", "c".repeat(32), alertId))
        assertEquals(2, output.shown.map { it.first.targetId }.distinct().size)
        val cabin = output.shown.last().first.targetId
        coordinator.activate("home", "d".repeat(32))
        val before = output.shown.size
        coordinator.receive(AlertSignal("1", "a".repeat(32), "alert_" + "f".repeat(32)))
        assertEquals(before, output.shown.size)
        assertTrue(output.active(cabin))
        assertFalse(output.active(output.shown.first().first.targetId))
        try {
            coordinator.activate("home", "a".repeat(32))
            fail("Retired registration cannot be activated again")
        } catch (_: InboxFailure) { }
    }

    @Test fun uncertainOutputAfterReservationDoesNotReplayAnInterruption() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val directory = temporary.newFolder()
        val output = Output().apply { failNext = true }
        val coordinator = NotificationCoordinator(settings, Reader { detail(alertId) }, directory, output)
        coordinator.activate("home", "a".repeat(32))
        val signal = AlertSignal("1", "a".repeat(32), alertId)
        try {
            coordinator.receive(signal)
            fail("Simulated uncertain OS output must stop this delivery")
        } catch (failure: InboxFailure) {
            assertEquals(Problem.SETTINGS, failure.problem)
        }
        NotificationCoordinator(settings, Reader { error("Uncertain hidden alert must not fetch") }, directory, output).receive(signal)
        assertTrue(output.shown.isEmpty())
    }

    @Test fun asynchronousOsVisibilityDoesNotCancelANewlyPostedNotification() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val output = Output().apply { visible = false }
        val response = CompletableDeferred<AlertDetail>()
        val reading = CompletableDeferred<Unit>()
        val coordinator = NotificationCoordinator(settings, Reader { reading.complete(Unit); response.await() }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        val delivery = async { coordinator.receive(AlertSignal("1", "a".repeat(32), alertId)) }
        withTimeout(1_000) { reading.await() }
        coordinator.receive(AlertSignal("1", "a".repeat(32), alertId))
        output.visible = true
        response.complete(detail(alertId))
        delivery.await()
        assertEquals("Original summary", output.shown.last().first.detail?.summary)
        assertEquals(1, output.shown.count { it.second })
    }

    @Test fun fastReadWaitsBrieflyForTheOsToPublishTheGenericNotification() = runBlocking {
        val settings = Settings(listOf(Connection.create("Home", "https://home.example", "secret", "home")))
        val output = Output().apply { visible = false }
        val coordinator = NotificationCoordinator(settings, Reader { detail(alertId) }, temporary.newFolder(), output)
        coordinator.activate("home", "a".repeat(32))
        val delivery = async { coordinator.receive(AlertSignal("1", "a".repeat(32), alertId)) }
        withTimeout(1_000) { output.invisibleCheck.await() }
        output.visible = true
        delivery.await()
        assertEquals("Original summary", output.shown.last().first.detail?.summary)
    }

    private class Settings(var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }

    private class Reader(val read: suspend () -> AlertDetail) : Backend {
        override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
        override suspend fun read(connection: Connection, id: String) = read()
    }

    private class Output : NotificationOutput {
        val shown = java.util.Collections.synchronizedList(mutableListOf<Pair<AlertNotice, Boolean>>())
        private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var allowed = true
        var failNext = false
        var visible = true
        val invisibleCheck = CompletableDeferred<Unit>()
        override fun enabled() = allowed
        override fun show(notice: AlertNotice, interrupt: Boolean) {
            if (failNext) { failNext = false; error("Simulated uncertain OS output") }
            shown += notice to interrupt
            active += notice.targetId
        }
        override fun active(targetId: String): Boolean {
            if (!visible) { invisibleCheck.complete(Unit); return false }
            return targetId in active
        }
        override fun cancel(targetId: String) { active -= targetId }
        override fun rename(targetId: String, source: String) {
            shown.lastOrNull { it.first.targetId == targetId }?.let { show(it.first.copy(source = source), false) }
        }
    }

    private fun detail(id: String) = AlertDetail(
        AlertRow(id, "2026-01-01T12:00:00Z", "Original title", "high", "2026-01-01T12:00:00Z", Presentation("fallback", 1)),
        "Original summary",
    )
}
