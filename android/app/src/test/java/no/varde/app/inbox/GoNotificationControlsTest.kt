package no.varde.app.inbox

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import no.varde.app.notifications.*
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class GoNotificationControlsTest {
    @Test fun coordinatorControlsKeepRealBackendBaselinesAndSourcesIsolated() = runBlocking {
        val backendDirectory = File(requireNotNull(System.getProperty("varde.backend.dir")))
        val metadata = Files.createTempDirectory("varde-controls-test-").toFile()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try {
            GoBackendFixture(backendDirectory).use { homeBackend ->
                GoBackendFixture(backendDirectory).use { cabinBackend ->
                    GoTlsProxy(homeBackend, certificate).use { homeProxy ->
                        GoTlsProxy(cabinBackend, certificate).use { cabinProxy ->
                            val home = homeProxy.connection("Home", homeBackend.installationSecret, "home")
                            val cabin = cabinProxy.connection("Cabin", cabinBackend.installationSecret, "cabin")
                            val settings = Settings(listOf(home, cabin))
                            val backend: Backend = HttpBackend(http)
                            val output = Output()
                            val coordinator = NotificationCoordinator(settings, backend, metadata, output)
                            val token = PushToken("fcm", "synthetic-private-destination")
                            homeBackend.submit(1)
                            cabinBackend.submit(1)
                            coordinator.prepareNotifications()
                            coroutineScope { settings.connections.map { async { coordinator.register(it.id, token) } }.awaitAll() }
                            coordinator.recoverAll()
                            assertTrue(output.summaries.isEmpty())
                            val originalHomeSession = requireNotNull(coordinator.currentRegistration("home"))
                            assertNotEquals(originalHomeSession, coordinator.currentRegistration("cabin"))
                            val individual = homeBackend.submit(2).second
                            coordinator.receive(AlertSignal("1", originalHomeSession, individual))
                            assertEquals(1, output.individuals.count { it.second })
                            assertEquals("Original 2", output.individuals.last().first.detail?.summary)
                            val target = output.individuals.first().first.targetId
                            coordinator.setNotificationsEnabled("home", false)
                            assertFalse(output.active(target))
                            assertNull(coordinator.resolveTap(target))
                            coordinator.unregisterObsolete(settings.connections)
                            assertFalse(backend.pushRegistration(home).enabled)
                            coordinator.recover("home")
                            homeBackend.submit(3)
                            cabinBackend.submit(2)
                            coordinator.recoverAll()
                            assertEquals(listOf("Cabin"), output.summaries.map { it.source })
                            assertEquals(1, output.summaries.single().count)
                            coordinator.setNotificationsEnabled("home", true)
                            coordinator.register("home", token)
                            coordinator.recover("home")
                            val restoredSession = requireNotNull(coordinator.currentRegistration("home"))
                            assertNotEquals(originalHomeSession, restoredSession)
                            coordinator.receive(AlertSignal("1", originalHomeSession, individual))
                            assertEquals(1, output.individuals.count { it.second })
                            assertEquals(listOf("Cabin"), output.summaries.map { it.source })
                            homeBackend.pause()
                            cabinBackend.submit(3)
                            coordinator.recoverAll()
                            assertEquals(Problem.NETWORK, coordinator.recoveryProblems.value["home"])
                            assertEquals(listOf("Cabin", "Cabin"), output.summaries.map { it.source })
                            homeBackend.resume()
                            coordinator.register("home", token)
                            assertEquals(restoredSession, coordinator.currentRegistration("home"))
                            homeBackend.rotateInstallation()
                            val replacement = homeProxy.connection("Home", homeBackend.installationSecret, "home")
                            settings.connections = listOf(replacement, cabin)
                            coordinator.synchronize()
                            coordinator.unregisterObsolete(listOf(home))
                            coordinator.register("home", token)
                            assertNotEquals(restoredSession, coordinator.currentRegistration("home"))
                            coordinator.recoverAll()
                            assertEquals(listOf("Cabin", "Cabin"), output.summaries.map { it.source })
                            GoTlsProxy(homeBackend, certificate).use { replacementPath ->
                                val previousSession = coordinator.currentRegistration("home")
                                val moved = replacementPath.connection("Home", homeBackend.installationSecret, "home")
                                settings.connections = listOf(moved, cabin)
                                coordinator.synchronize()
                                coordinator.unregisterObsolete(listOf(replacement))
                                coordinator.register("home", token)
                                assertNotEquals(previousSession, coordinator.currentRegistration("home"))
                                coordinator.recover("home")
                                assertEquals(listOf("Cabin", "Cabin"), output.summaries.map { it.source })
                                settings.connections = listOf(cabin)
                                coordinator.synchronize()
                                coordinator.unregisterObsolete(listOf(moved))
                                assertFalse(backend.pushRegistration(moved).enabled)
                                assertEquals(3, backend.history(moved).items.size)
                                assertNull(coordinator.currentRegistration("home"))
                            }
                            val persisted = File(metadata, "metadata.json").readText()
                            assertFalse(persisted.contains(home.secret))
                            assertFalse(persisted.contains("Original 2"))
                            assertFalse(persisted.contains("Alert 2"))
                        }
                    }
                }
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            metadata.deleteRecursively()
        }
    }

    @Test fun serverVersionChangesPreserveRecoveryButExplicitRemovalFencesTheOldSession() = runBlocking {
        val backendDirectory = File(requireNotNull(System.getProperty("varde.backend.dir")))
        val metadata = Files.createTempDirectory("varde-token-repair-test-").toFile()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try {
            GoBackendFixture(backendDirectory).use { fixture ->
                GoTlsProxy(fixture, certificate).use { proxy ->
                    val connection = proxy.connection("Home", fixture.installationSecret, "home")
                    val backend: Backend = HttpBackend(http)
                    val output = Output()
                    val coordinator = NotificationCoordinator(Settings(listOf(connection)), backend, metadata, output)
                    coordinator.prepareNotifications()
                    coordinator.register("home", PushToken("fcm", "synthetic-token-a"))
                    val original = backend.pushRegistration(connection)
                    val registrationId = requireNotNull(original.registrationId)
                    fixture.submit(1)
                    coordinator.recover("home")
                    fixture.submit(2)
                    backend.putPush(connection, PushMutation(original.version, "a".repeat(32)),
                        PushDestination(registrationId, "fcm", "synthetic-server-token"))
                    val replacement = PushToken("fcm", "synthetic-token-b")
                    coordinator.register("home", replacement)
                    coordinator.recover("home")
                    assertEquals(listOf(1, 1), output.summaries.map { it.count })
                    assertTrue(registrationId == coordinator.currentRegistration("home"))
                    val repaired = backend.pushRegistration(connection)
                    assertEquals(original.baselineCursor, repaired.baselineCursor)
                    val serverChange = backend.putPush(connection, PushMutation(repaired.version, "b".repeat(32)),
                        PushDestination(registrationId, "fcm", "synthetic-other-server-token"))
                    coordinator.register("home", replacement)
                    val reasserted = backend.pushRegistration(connection)
                    assertEquals(serverChange.version + 1, reasserted.version)
                    assertEquals(original.baselineCursor, reasserted.baselineCursor)
                    fixture.restart()
                    coordinator.register("home", replacement)
                    assertEquals(reasserted.version, backend.pushRegistration(connection).version)
                    backend.deletePush(connection, PushMutation(reasserted.version, "c".repeat(32)))
                    coordinator.register("home", replacement)
                    assertFalse(backend.pushRegistration(connection).enabled)
                    assertNull(coordinator.currentRegistration("home"))
                    assertEquals(Problem.REGISTRATION_CHANGED, coordinator.notificationControls.value["home"]?.problem)
                    coordinator.register("home", replacement)
                    assertTrue(registrationId != coordinator.currentRegistration("home"))
                    coordinator.recover("home")
                    assertEquals(listOf(1, 1), output.summaries.map { it.count })
                }
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            metadata.deleteRecursively()
        }
    }

    private class Settings(var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }
    private class Output : NotificationOutput {
        val individuals = mutableListOf<Pair<AlertNotice, Boolean>>()
        val summaries = mutableListOf<CatchUpNotice>()
        private val targets = mutableSetOf<String>()
        override fun enabled() = true
        override fun show(notice: AlertNotice, interrupt: Boolean) { individuals += notice to interrupt; targets += notice.targetId }
        override fun catchUp(notice: CatchUpNotice, interrupt: Boolean) { summaries += notice; targets += notice.targetId }
        override fun active(targetId: String) = targetId in targets
        override fun cancel(targetId: String) { targets -= targetId }
        override fun rename(targetId: String, source: String) = Unit
    }
}
