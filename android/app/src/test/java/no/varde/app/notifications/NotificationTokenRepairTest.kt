package no.varde.app.notifications

import kotlinx.coroutines.runBlocking
import no.varde.app.inbox.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationTokenRepairTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = Connection.create("Synthetic", "https://synthetic.example", "synthetic-secret", "synthetic")

    @Test fun permanentDestinationRepairPreservesSessionCursorAndMissedAcceptance() = runBlocking {
        val backend = RepairBackend()
        val output = Output()
        val coordinator = NotificationCoordinator(Settings(connection), backend, temporary.newFolder(), output)
        coordinator.prepareNotifications()
        coordinator.register(connection.id, PushToken("fcm", "synthetic-token-a"))
        val originalSession = coordinator.currentRegistration(connection.id)
        coordinator.recover(connection.id)
        backend.disableDestination()
        coordinator.register(connection.id, PushToken("fcm", "synthetic-token-b"))
        coordinator.recover(connection.id)
        assertEquals("Repair must not silence an eligible unrecovered acceptance", 1, output.catchUpCount)
        assertTrue("Token repair must preserve its session", originalSession == coordinator.currentRegistration(connection.id))
        assertEquals(listOf("baseline", "progress"), backend.changeRequests)
        assertEquals(NotificationActivation.ACTIVE, coordinator.notificationControls.value[connection.id]?.activation)
    }

    @Test fun unchangedTokenReconnectRepairsDisabledDestinationWithoutResettingRecovery() = runBlocking {
        val backend = RepairBackend()
        val output = Output()
        val coordinator = NotificationCoordinator(Settings(connection), backend, temporary.newFolder(), output)
        val token = PushToken("fcm", "synthetic-token")
        coordinator.prepareNotifications()
        coordinator.register(connection.id, token)
        val originalSession = coordinator.currentRegistration(connection.id)
        coordinator.recover(connection.id)
        backend.disableDestination()
        coordinator.register(connection.id, token)
        assertTrue("Reconnect must repair a disabled destination", backend.pushRegistration(connection).enabled)
        coordinator.recover(connection.id)
        assertTrue(originalSession == coordinator.currentRegistration(connection.id))
        assertEquals(listOf("baseline", "progress"), backend.changeRequests)
        assertEquals(1, output.catchUpCount)
        assertEquals(2, backend.puts.size)
        coordinator.register(connection.id, token)
        assertEquals("A healthy reconnect must not replace queued destination work", 2, backend.puts.size)
    }

    @Test fun preconditionRaceReloadsTheVersionWithoutRetiringTheRecoverableSession() = runBlocking {
        val backend = RepairBackend()
        val output = Output()
        val coordinator = NotificationCoordinator(Settings(connection), backend, temporary.newFolder(), output)
        coordinator.prepareNotifications()
        coordinator.register(connection.id, PushToken("fcm", "synthetic-token-a"))
        val originalSession = coordinator.currentRegistration(connection.id)
        coordinator.recover(connection.id)
        backend.disableBeforeNextPut = true
        coordinator.register(connection.id, PushToken("fcm", "synthetic-token-b"))
        coordinator.recover(connection.id)
        assertEquals(1, output.catchUpCount)
        assertTrue(originalSession == coordinator.currentRegistration(connection.id))
        assertEquals(listOf("baseline", "progress"), backend.changeRequests)
        assertEquals(3, backend.puts.size)
        assertEquals(1L, backend.puts[1].version)
        assertEquals(2L, backend.puts[2].version)
        assertNotEquals("A changed precondition requires a fresh idempotency key", backend.puts[1].key, backend.puts[2].key)
    }

    @Test fun explicitlyRemovedSessionCannotBeReactivatedByTokenRepair() = runBlocking {
        val backend = RepairBackend()
        val coordinator = NotificationCoordinator(Settings(connection), backend, temporary.newFolder(), Output())
        val token = PushToken("fcm", "synthetic-token")
        coordinator.prepareNotifications()
        coordinator.register(connection.id, token)
        val retiredSession = coordinator.currentRegistration(connection.id)
        backend.removeDestination()
        coordinator.register(connection.id, token)
        assertFalse(backend.pushRegistration(connection).enabled)
        assertNull(coordinator.currentRegistration(connection.id))
        assertEquals(NotificationActivation.PENDING, coordinator.notificationControls.value[connection.id]?.activation)
        assertEquals(Problem.REGISTRATION_CHANGED, coordinator.notificationControls.value[connection.id]?.problem)
        assertEquals(2, backend.puts.size)
        coordinator.register(connection.id, token)
        assertTrue(retiredSession != coordinator.currentRegistration(connection.id))
        assertTrue(backend.pushRegistration(connection).enabled)
    }

    @Test fun uncertainRepairReplaysItsExactMutationAfterRestartAndKeepsTheCursor() = runBlocking {
        val backend = RepairBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(Settings(connection), backend, directory, output)
        coordinator.prepareNotifications()
        coordinator.register(connection.id, PushToken("fcm", "synthetic-token-a"))
        val originalSession = coordinator.currentRegistration(connection.id)
        coordinator.recover(connection.id)
        backend.disableDestination()
        backend.failNextAcknowledgement = true
        val replacement = PushToken("fcm", "synthetic-token-b")
        coordinator.register(connection.id, replacement)
        assertEquals(NotificationActivation.PENDING, coordinator.notificationControls.value[connection.id]?.activation)
        val readsBeforeRestart = backend.registrationReads
        val restarted = NotificationCoordinator(Settings(connection), backend, directory, output)
        restarted.prepareNotifications()
        restarted.register(connection.id, replacement)
        assertEquals("Replay must precede looking up a newer precondition", readsBeforeRestart, backend.registrationReads)
        assertEquals(backend.puts[1].key, backend.puts[2].key)
        assertEquals(2L, backend.puts[2].version)
        restarted.recover(connection.id)
        assertTrue(originalSession == restarted.currentRegistration(connection.id))
        assertEquals(listOf("baseline", "progress"), backend.changeRequests)
        assertEquals(1, output.catchUpCount)
    }

    @Test fun repairSurvivesRestartBeforeItsPreconditionCanBeRead() = runBlocking {
        val backend = RepairBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(Settings(connection), backend, directory, output)
        coordinator.prepareNotifications()
        coordinator.register(connection.id, PushToken("fcm", "synthetic-token-a"))
        val originalSession = coordinator.currentRegistration(connection.id)
        coordinator.recover(connection.id)
        backend.disableDestination()
        backend.failNextRead = true
        val replacement = PushToken("fcm", "synthetic-token-b")
        coordinator.register(connection.id, replacement)
        assertEquals(Problem.NETWORK, coordinator.notificationControls.value[connection.id]?.problem)
        assertEquals(1, backend.puts.size)
        val restarted = NotificationCoordinator(Settings(connection), backend, directory, output)
        restarted.prepareNotifications()
        restarted.register(connection.id, replacement)
        restarted.recover(connection.id)
        assertTrue(originalSession == restarted.currentRegistration(connection.id))
        assertEquals(listOf("baseline", "progress"), backend.changeRequests)
        assertEquals(1, output.catchUpCount)
    }

    @Test fun repeatedVersionRacesAreBoundedAndResumeWithoutResettingTheSession() = runBlocking {
        val backend = RepairBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(Settings(connection), backend, directory, output)
        coordinator.prepareNotifications()
        coordinator.register(connection.id, PushToken("fcm", "synthetic-token-a"))
        val originalSession = coordinator.currentRegistration(connection.id)
        coordinator.recover(connection.id)
        backend.versionRacesRemaining = 2
        val replacement = PushToken("fcm", "synthetic-token-b")
        coordinator.register(connection.id, replacement)
        assertEquals("One registration call must not retry without a bound", 3, backend.puts.size)
        assertEquals(NotificationActivation.PENDING, coordinator.notificationControls.value[connection.id]?.activation)
        assertTrue(originalSession == coordinator.currentRegistration(connection.id))
        val restarted = NotificationCoordinator(Settings(connection), backend, directory, output)
        restarted.prepareNotifications()
        restarted.register(connection.id, replacement)
        restarted.recover(connection.id)
        assertTrue(originalSession == restarted.currentRegistration(connection.id))
        assertEquals(listOf("baseline", "progress"), backend.changeRequests)
        assertEquals(1, output.catchUpCount)
    }

    private class Settings(val connection: Connection) : ConnectionSettings {
        override suspend fun load() = listOf(connection)
        override suspend fun save(connections: List<Connection>) = Unit
    }

    private class Output : NotificationOutput {
        var catchUpCount = 0
        override fun enabled() = true
        override fun show(notice: AlertNotice, interrupt: Boolean) = Unit
        override fun catchUp(notice: CatchUpNotice, interrupt: Boolean) { catchUpCount += notice.count }
        override fun active(targetId: String) = true
        override fun cancel(targetId: String) = Unit
        override fun rename(targetId: String, source: String) = Unit
    }

    private class RepairBackend : Backend {
        private var current = PushRegistration(null, null, false, null, 0)
        private var retainedId: String? = null
        private var retainedBaseline: String? = null
        private var currentBaseline = "baseline"
        private val retiredIds = mutableSetOf<String>()
        val changeRequests = mutableListOf<String>()
        val puts = mutableListOf<PushMutation>()
        var disableBeforeNextPut = false
        var failNextAcknowledgement = false
        var registrationReads = 0
        var failNextRead = false
        var versionRacesRemaining = 0
        private val committed = mutableMapOf<String, CommittedMutation>()
        private class CommittedMutation(val version: Long, val destination: PushDestination, val result: PushRegistration)
        override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
        override suspend fun read(connection: Connection, id: String): AlertDetail = error("Unexpected original read")
        override suspend fun pushRegistration(connection: Connection): PushRegistration {
            registrationReads++
            if (failNextRead) { failNextRead = false; throw InboxFailure(Problem.NETWORK) }
            return current
        }
        override suspend fun putPush(connection: Connection, mutation: PushMutation, destination: PushDestination): PushRegistration {
            puts += mutation
            committed[mutation.key]?.let { previous ->
                if (previous.version != mutation.version || previous.destination.registrationId != destination.registrationId ||
                    previous.destination.provider != destination.provider || previous.destination.destination != destination.destination) {
                    throw InboxFailure(Problem.IDEMPOTENCY_CONFLICT)
                }
                return previous.result
            }
            if (disableBeforeNextPut || versionRacesRemaining > 0) {
                disableBeforeNextPut = false
                if (versionRacesRemaining > 0) versionRacesRemaining--
                disableDestination()
            }
            if (mutation.version != current.version || destination.registrationId in retiredIds) throw InboxFailure(Problem.REGISTRATION_CHANGED)
            if (destination.registrationId != retainedId) {
                retainedId = destination.registrationId
                retainedBaseline = currentBaseline
            }
            current = PushRegistration(retainedId, "fcm", true, retainedBaseline, mutation.version + 1)
            committed[mutation.key] = CommittedMutation(mutation.version, destination, current)
            if (failNextAcknowledgement) { failNextAcknowledgement = false; throw InboxFailure(Problem.NETWORK) }
            return current
        }
        fun disableDestination() {
            current = PushRegistration(null, null, false, null, current.version + 1)
            currentBaseline = "now-after-missed-acceptance"
        }
        fun removeDestination() {
            retainedId?.let { retiredIds += it }
            disableDestination()
            retainedId = null
            retainedBaseline = null
        }
        override suspend fun changes(connection: Connection, cursor: String): ChangePage {
            changeRequests += cursor
            return when (cursor) {
                "baseline" -> ChangePage(emptyList(), "progress", false)
                "progress" -> ChangePage(listOf(ChangeEntry("alert_" + "a".repeat(32), "accepted", 0)), "after", false)
                else -> ChangePage(emptyList(), cursor, false)
            }
        }
    }
}
