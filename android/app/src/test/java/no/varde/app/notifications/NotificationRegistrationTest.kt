package no.varde.app.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import no.varde.app.inbox.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationRegistrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val home = Connection.create("Home", "https://home.example", "synthetic-secret", "home")
    private val alert = "alert_" + "a".repeat(32)

    @Test fun proposedIdentityIsDurableAndPreAcknowledgementSignalsRecoverWithoutAGap() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RegistrationBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(settings, backend, directory, output)
        coordinator.prepareNotifications()
        backend.gate = CompletableDeferred()
        val activation = async { coordinator.register("home", PushToken("fcm", "synthetic-private-token")) }
        backend.requested.await()
        val proposed = requireNotNull(coordinator.currentRegistration("home"))
        assertEquals(proposed, NotificationCoordinator(settings, backend, directory, output).currentRegistration("home"))
        assertEquals(NotificationActivation.PENDING, coordinator.notificationControls.value["home"]?.activation)
        coordinator.receive(AlertSignal("1", proposed, alert))
        coordinator.recover("home")
        assertTrue(output.individuals.isEmpty())
        assertTrue(backend.changeRequests.isEmpty())
        backend.page = ChangePage(listOf(ChangeEntry(alert, "accepted", 0)), "after", false)
        backend.gate!!.complete(Unit)
        activation.await()
        assertEquals(NotificationActivation.ACTIVE, coordinator.notificationControls.value["home"]?.activation)
        coordinator.recover("home")
        assertEquals(listOf("baseline"), backend.changeRequests)
        assertEquals(1, output.summaries.single().count)
        coordinator.receive(AlertSignal("1", proposed, alert))
        assertTrue(output.individuals.isEmpty())
    }

    @Test fun uncertainRegistrationRetriesItsCommittedRequestAndRefreshKeepsRecoveryPosition() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RegistrationBackend().apply { failAcknowledgement = true }
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(settings, backend, directory, output)
        coordinator.prepareNotifications()
        val token = PushToken("fcm", "synthetic-private-token")
        coordinator.register("home", token)
        val proposed = coordinator.currentRegistration("home")
        assertEquals(NotificationActivation.PENDING, coordinator.notificationControls.value["home"]?.activation)
        assertEquals(Problem.NETWORK, coordinator.notificationControls.value["home"]?.problem)
        backend.baseline = "later-baseline-must-not-skip-arrivals"
        val restarted = NotificationCoordinator(settings, backend, directory, output)
        restarted.prepareNotifications()
        restarted.register("home", token)
        assertEquals(proposed, restarted.currentRegistration("home"))
        assertEquals(backend.puts[0].first.key, backend.puts[1].first.key)
        assertEquals(0L, backend.puts[1].first.version)
        backend.page = ChangePage(listOf(ChangeEntry(alert, "accepted", 0)), "after", false)
        restarted.recover("home")
        assertEquals(1, output.summaries.single().count)
        restarted.register("home", PushToken("fcm", "synthetic-rotated-token"))
        assertEquals(proposed, restarted.currentRegistration("home"))
        assertNotEquals(backend.puts[1].first.key, backend.puts[2].first.key)
        restarted.recover("home")
        assertEquals(listOf("baseline", "after"), backend.changeRequests)
        assertEquals(1, output.summaries.size)
    }

    @Test fun muteCancelsLocallyAndReenableLeavesMutedRecoverySilent() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RegistrationBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(settings, backend, directory, output)
        val token = PushToken("fcm", "synthetic-private-token")
        coordinator.prepareNotifications()
        coordinator.register("home", token)
        val previous = requireNotNull(coordinator.currentRegistration("home"))
        coordinator.receive(AlertSignal("1", previous, alert))
        val target = output.individuals.single().targetId
        coordinator.setNotificationsEnabled("home", false)
        assertEquals(NotificationActivation.MUTED, coordinator.notificationControls.value["home"]?.activation)
        assertFalse(output.active(target))
        assertNull(coordinator.resolveTap(target))
        backend.removeFailure = true
        coordinator.unregisterObsolete(settings.load())
        coordinator.receive(AlertSignal("1", previous, "alert_" + "b".repeat(32)))
        coordinator.recover("home")
        backend.page = ChangePage(listOf(ChangeEntry("alert_" + "b".repeat(32), "accepted", 0)), "after-muted", false)
        coordinator.recover("home")
        assertTrue(output.summaries.isEmpty())
        val restarted = NotificationCoordinator(settings, backend, directory, output)
        restarted.prepareNotifications()
        assertEquals(false, restarted.notificationControls.value["home"]?.enabled)
        restarted.setNotificationsEnabled("home", true)
        backend.baseline = "fresh-after-muted"
        restarted.register("home", token)
        assertNotEquals(previous, restarted.currentRegistration("home"))
        backend.page = ChangePage(emptyList(), "after-fresh", false)
        restarted.recover("home")
        assertEquals("fresh-after-muted", backend.changeRequests.last())
        assertTrue(output.summaries.isEmpty())
        assertEquals(1, output.individuals.size)
    }

    @Test fun permissionRestorationRequiresAFreshSessionAndNeverRepeatsThePromptReservation() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RegistrationBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(settings, backend, directory, output)
        val token = PushToken("fcm", "synthetic-private-token")
        coordinator.prepareNotifications()
        coordinator.register("home", token)
        val previous = requireNotNull(coordinator.currentRegistration("home"))
        output.allowed = false
        coordinator.prepareNotifications()
        assertEquals(NotificationActivation.PERMISSION_REQUIRED, coordinator.notificationControls.value["home"]?.activation)
        coordinator.receive(AlertSignal("1", previous, alert))
        coordinator.recover("home")
        assertTrue(coordinator.reservePermissionPrompt())
        assertFalse(coordinator.reservePermissionPrompt())
        val restarted = NotificationCoordinator(settings, backend, directory, output)
        assertFalse(restarted.reservePermissionPrompt())
        output.allowed = true
        restarted.prepareNotifications()
        assertEquals(NotificationActivation.PENDING, restarted.notificationControls.value["home"]?.activation)
        backend.baseline = "fresh-after-denial"
        restarted.register("home", token)
        assertNotEquals(previous, restarted.currentRegistration("home"))
        restarted.receive(AlertSignal("1", previous, alert))
        assertTrue(output.individuals.isEmpty())
        assertTrue(output.summaries.isEmpty())
    }

    @Test fun mutingDuringRegistrationCannotBeUndoneByALateAcknowledgement() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RegistrationBackend().apply { gate = CompletableDeferred() }
        val output = Output()
        val coordinator = NotificationCoordinator(settings, backend, temporary.newFolder(), output)
        coordinator.prepareNotifications()
        val registering = async { coordinator.register("home", PushToken("fcm", "synthetic-private-token")) }
        backend.requested.await()
        val obsolete = requireNotNull(coordinator.currentRegistration("home"))
        coordinator.setNotificationsEnabled("home", false)
        backend.gate!!.complete(Unit)
        registering.await()
        assertFalse(backend.current.enabled)
        assertEquals(NotificationActivation.MUTED, coordinator.notificationControls.value["home"]?.activation)
        coordinator.receive(AlertSignal("1", obsolete, alert))
        assertTrue(output.individuals.isEmpty())
    }

    @Test fun obsoleteMutationPreconditionRetiresLocalEligibilityBeforeFreshActivation() = runBlocking {
        val backend = RegistrationBackend()
        val output = Output()
        val coordinator = NotificationCoordinator(Settings(listOf(home)), backend, temporary.newFolder(), output)
        coordinator.prepareNotifications()
        coordinator.register("home", PushToken("fcm", "synthetic-token"))
        val previous = requireNotNull(coordinator.currentRegistration("home"))
        backend.current = PushRegistration("f".repeat(32), "fcm", true, "other-session", 2)
        coordinator.register("home", PushToken("fcm", "synthetic-rotated-token"))
        assertEquals(NotificationActivation.PENDING, coordinator.notificationControls.value["home"]?.activation)
        assertEquals(Problem.REGISTRATION_CHANGED, coordinator.notificationControls.value["home"]?.problem)
        coordinator.receive(AlertSignal("1", previous, alert))
        assertTrue(output.individuals.isEmpty())
        backend.baseline = "fresh-after-conflict"
        coordinator.register("home", PushToken("fcm", "synthetic-rotated-token"))
        assertNotEquals(previous, coordinator.currentRegistration("home"))
        assertEquals(NotificationActivation.ACTIVE, coordinator.notificationControls.value["home"]?.activation)
    }

    @Test fun adoptingControlsRetiresLegacyEligibilityBeforeWaitingForAToken() = runBlocking {
        val backend = RegistrationBackend()
        val output = Output()
        val coordinator = NotificationCoordinator(Settings(listOf(home)), backend, temporary.newFolder(), output)
        coordinator.activate("home", "f".repeat(32))
        coordinator.receive(AlertSignal("1", "f".repeat(32), alert))
        val target = output.individuals.single().targetId
        coordinator.prepareNotifications()
        assertFalse(output.active(target))
        coordinator.receive(AlertSignal("1", "f".repeat(32), "alert_" + "b".repeat(32)))
        coordinator.register("home", null)
        coordinator.recover("home")
        assertEquals(NotificationActivation.PENDING, coordinator.notificationControls.value["home"]?.activation)
        assertTrue(backend.changeRequests.isEmpty())
        assertEquals(1, output.individuals.size)
    }

    @Test fun completedRemovalsDoNotStarveLaterMuteOperations() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RegistrationBackend()
        val coordinator = NotificationCoordinator(settings, backend, temporary.newFolder(), Output())
        coordinator.prepareNotifications()
        repeat(6) {
            coordinator.setNotificationsEnabled("home", true)
            coordinator.register("home", PushToken("fcm", "synthetic-token"))
            coordinator.setNotificationsEnabled("home", false)
            coordinator.unregisterObsolete(settings.load())
            assertFalse(backend.current.enabled)
        }
        val requests = backend.registrationReads
        coordinator.unregisterObsolete(settings.load())
        assertEquals(requests, backend.registrationReads)
    }

    @Test fun failedMutePersistenceStillCancelsAndFencesLocalOutputUntilRetry() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RegistrationBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(settings, backend, directory, output)
        coordinator.prepareNotifications()
        coordinator.register("home", PushToken("fcm", "synthetic-token"))
        val previous = requireNotNull(coordinator.currentRegistration("home"))
        coordinator.receive(AlertSignal("1", previous, alert))
        val target = output.individuals.single().targetId
        val obstruction = java.io.File(directory, "metadata.new")
        assertTrue(obstruction.mkdir())
        try { coordinator.setNotificationsEnabled("home", false); fail("Unwritable metadata accepted") }
        catch (failure: InboxFailure) { assertEquals(Problem.SETTINGS, failure.problem) }
        assertFalse(output.active(target))
        assertEquals(Problem.SETTINGS, coordinator.notificationControls.value["home"]?.problem)
        assertTrue(obstruction.delete())
        coordinator.receive(AlertSignal("1", previous, "alert_" + "b".repeat(32)))
        assertEquals(1, output.individuals.size)
        assertNull(coordinator.resolveTap(target))
        coordinator.setNotificationsEnabled("home", false)
        val restarted = NotificationCoordinator(settings, backend, directory, output)
        restarted.prepareNotifications()
        assertEquals(false, restarted.notificationControls.value["home"]?.enabled)
    }

    @Test fun explicitLocalFixturesRespectMuteAndNeverInitializeAutomaticRecovery() = runBlocking {
        val backend = RegistrationBackend()
        val output = Output()
        val coordinator = NotificationCoordinator(Settings(listOf(home)), backend, temporary.newFolder(), output)
        coordinator.prepareNotifications()
        coordinator.register("home", null)
        assertNull(coordinator.currentRegistration("home", includePending = false))
        coordinator.activate("home", "f".repeat(32))
        coordinator.receive(AlertSignal("1", "f".repeat(32), alert))
        assertEquals(1, output.individuals.size)
        coordinator.recover("home")
        assertTrue(backend.changeRequests.isEmpty())
        coordinator.setNotificationsEnabled("home", false)
        coordinator.activate("home", "e".repeat(32))
        coordinator.receive(AlertSignal("1", "e".repeat(32), "alert_" + "b".repeat(32)))
        assertEquals(1, output.individuals.size)
        assertNull(coordinator.currentRegistration("home", includePending = false))
    }

    private class Settings(var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }
    private class RegistrationBackend : Backend {
        var gate: CompletableDeferred<Unit>? = null
        var failAcknowledgement = false
        var removeFailure = false
        var registrationReads = 0
        var baseline = "baseline"
        var current = PushRegistration(null, null, false, null, 0)
        val puts = mutableListOf<Pair<PushMutation, PushDestination>>()
        private val results = mutableMapOf<String, PushRegistration>()
        val requested = CompletableDeferred<Unit>()
        var page = ChangePage(emptyList(), "after", false)
        val changeRequests = mutableListOf<String>()
        override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
        override suspend fun read(connection: Connection, id: String): AlertDetail = throw InboxFailure(Problem.NETWORK)
        override suspend fun checkpoint(connection: Connection): String = "silent-muted"
        override suspend fun pushRegistration(connection: Connection): PushRegistration { registrationReads++; return current }
        override suspend fun putPush(connection: Connection, mutation: PushMutation, destination: PushDestination): PushRegistration {
            puts += mutation to destination
            requested.complete(Unit)
            gate?.await()
            val result = results.getOrPut(mutation.key) {
                if (mutation.version != current.version) throw InboxFailure(Problem.REGISTRATION_CHANGED)
                val cursor = if (current.registrationId == destination.registrationId) current.baselineCursor else baseline
                PushRegistration(destination.registrationId, destination.provider, true, cursor, mutation.version + 1).also { current = it }
            }
            if (failAcknowledgement) { failAcknowledgement = false; throw InboxFailure(Problem.NETWORK) }
            return result
        }
        override suspend fun deletePush(connection: Connection, mutation: PushMutation): PushRegistration {
            if (removeFailure) throw InboxFailure(Problem.NETWORK)
            if (mutation.version != current.version) throw InboxFailure(Problem.REGISTRATION_CHANGED)
            current = PushRegistration(null, null, false, null, mutation.version + 1)
            return current
        }
        override suspend fun changes(connection: Connection, cursor: String): ChangePage {
            changeRequests += cursor
            return page
        }
    }
    private class Output : NotificationOutput {
        var allowed = true
        val individuals = mutableListOf<AlertNotice>()
        val summaries = mutableListOf<CatchUpNotice>()
        val targets = mutableSetOf<String>()
        override fun enabled() = allowed
        override fun show(notice: AlertNotice, interrupt: Boolean) { individuals += notice; targets += notice.targetId }
        override fun catchUp(notice: CatchUpNotice, interrupt: Boolean) { summaries += notice; targets += notice.targetId }
        override fun active(targetId: String) = targetId in targets
        override fun cancel(targetId: String) { targets -= targetId }
        override fun rename(targetId: String, source: String) = Unit
    }
}
