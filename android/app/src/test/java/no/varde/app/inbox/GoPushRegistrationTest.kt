package no.varde.app.inbox

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class GoPushRegistrationTest {
    @Test fun registrationReplayRefreshAndRemovalAgreeWithGoAcrossRestart() = runBlocking {
        val directory = File(requireNotNull(System.getProperty("varde.backend.dir")))
        GoBackendFixture(directory).use { fixture ->
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            try {
                GoTlsProxy(fixture, certificate).use { proxy ->
                    val connection = proxy.connection("Home", fixture.installationSecret, "home")
                    val backend: Backend = BoundedBackend(HttpBackend(http)) { null }
                    val empty = backend.pushRegistration(connection)
                    assertFalse(empty.enabled)
                    assertEquals(0L, empty.version)
                    assertNull(empty.registrationId)
                    fixture.submit(1)
                    val proposed = PushDestination("1".repeat(32), "fcm", "synthetic-private-token")
                    val initial = PushMutation(0, "a".repeat(32))
                    val registered = backend.putPush(connection, initial, proposed)
                    assertTrue(registered.enabled)
                    assertEquals(1L, registered.version)
                    val baseline = requireNotNull(registered.baselineCursor)
                    assertTrue(backend.changes(connection, baseline).items.none { it.kind == "accepted" })
                    val arrival = fixture.submit(2).second
                    val refreshed = backend.putPush(connection, PushMutation(1, "b".repeat(32)),
                        PushDestination(proposed.registrationId, "fcm", "synthetic-rotated-token"))
                    assertEquals(2L, refreshed.version)
                    assertEquals(baseline, refreshed.baselineCursor)
                    fixture.restart()
                    assertEquals(registered, backend.putPush(connection, initial, proposed))
                    assertEquals(refreshed, backend.pushRegistration(connection))
                    assertEquals(listOf(arrival), backend.changes(connection, baseline).items.filter { it.kind == "accepted" }.map { it.alertId })
                    expectProblem(Problem.REGISTRATION_CHANGED) {
                        backend.deletePush(connection, PushMutation(1, "c".repeat(32)))
                    }
                    expectProblem(Problem.IDEMPOTENCY_CONFLICT) {
                        backend.putPush(connection, initial, PushDestination(proposed.registrationId, "fcm", "different-token"))
                    }
                    val removed = backend.deletePush(connection, PushMutation(2, "c".repeat(32)))
                    assertFalse(removed.enabled)
                    assertNull(removed.baselineCursor)
                    assertEquals(3L, removed.version)
                    fixture.restart()
                    assertEquals(removed, backend.deletePush(connection, PushMutation(2, "c".repeat(32))))
                    expectProblem(Problem.REGISTRATION_CHANGED) {
                        backend.putPush(connection, PushMutation(3, "d".repeat(32)), proposed)
                    }
                    fixture.submit(3)
                    val fresh = backend.putPush(connection, PushMutation(3, "d".repeat(32)), PushDestination("2".repeat(32), "fcm", proposed.destination))
                    assertTrue(backend.changes(connection, requireNotNull(fresh.baselineCursor)).items.none { it.kind == "accepted" })
                    fixture.rotateInstallation()
                    expectProblem(Problem.AUTHENTICATION) { backend.pushRegistration(connection) }
                    assertEquals(fresh, backend.pushRegistration(proxy.connection("Home", fixture.installationSecret, "home")))
                }
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test fun separatePhonesKeepTheirOwnMutationAndReplayNamespacesThroughOutage() = runBlocking {
        val directory = File(requireNotNull(System.getProperty("varde.backend.dir")))
        GoBackendFixture(directory).use { fixture ->
            val secondSecret = fixture.additionalInstallation()
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            try {
                GoTlsProxy(fixture, certificate).use { proxy ->
                    val first = proxy.connection("Phone one", fixture.installationSecret, "one")
                    val second = proxy.connection("Phone two", secondSecret, "two")
                    val backend: Backend = BoundedBackend(HttpBackend(http)) { null }
                    val mutation = PushMutation(0, "a".repeat(32))
                    val destination = PushDestination("1".repeat(32), "fcm", "synthetic-private-token")
                    val registered = backend.putPush(first, mutation, destination)
                    assertEquals(0L, backend.pushRegistration(second).version)
                    assertFalse(backend.pushRegistration(second).enabled)
                    fixture.pause()
                    expectProblem(Problem.NETWORK) { backend.putPush(second, mutation, destination) }
                    fixture.resume()
                    val secondRegistered = backend.putPush(second, mutation, destination)
                    assertEquals(1L, secondRegistered.version)
                    assertEquals(registered, backend.pushRegistration(first))
                    backend.deletePush(first, PushMutation(1, "b".repeat(32)))
                    assertEquals(secondRegistered, backend.pushRegistration(second))
                    assertEquals(secondRegistered, backend.putPush(second, mutation, destination))
                    fixture.rotateInstallation()
                    expectProblem(Problem.AUTHENTICATION) { backend.putPush(first, mutation, destination) }
                    assertEquals(secondRegistered, backend.pushRegistration(second))
                }
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }

    private suspend fun expectProblem(expected: Problem, operation: suspend () -> Any) {
        try { operation(); fail("Expected safe registration failure") }
        catch (failure: InboxFailure) { assertEquals(expected, failure.problem) }
    }
}
