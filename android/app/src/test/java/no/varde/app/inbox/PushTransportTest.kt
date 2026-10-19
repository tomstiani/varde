package no.varde.app.inbox

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class PushTransportTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val active = """{"registrationId":"${"1".repeat(32)}","provider":"fcm","enabled":true,"baselineCursor":"PRIVATE_cursor"}"""
    private val disabled = """{"registrationId":null,"provider":null,"enabled":false,"baselineCursor":null}"""

    @Test fun privateRegistrationFieldsAreNotAutomaticDiagnosticStrings() = runTest {
        MockWebServer().use { server ->
            server.useHttps(serverTrust.sslSocketFactory(), false)
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
            try {
                server.enqueue(MockResponse().setHeader("ETag", "\"1\"").setBody(active))
                val connection = Connection.create("Home", server.url("/prefix/").toString(), "PRIVATE_credential")
                val registration = HttpBackend(http).pushRegistration(connection)
                assertTrue(registration.enabled)
                assertFalse(registration.toString().contains("1".repeat(32)))
                assertFalse(registration.toString().contains("PRIVATE_cursor"))
                val request = server.takeRequest()
                assertEquals("/prefix/v1/installation/push", request.path)
                assertEquals("Bearer PRIVATE_credential", request.getHeader("Authorization"))
                assertEquals("no-store", request.getHeader("Cache-Control"))
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test fun registrationErrorsStayLocalAndDoNotFollowRedirects() = runTest {
        MockWebServer().use { server ->
            server.useHttps(serverTrust.sslSocketFactory(), false)
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
            try {
                val backend = HttpBackend(http)
                val connection = Connection.create("Home", server.url("/").toString(), "PRIVATE_credential")
                val cases = listOf(
                    Triple(400, "invalid_cursor", Problem.RESPONSE),
                    Triple(409, "idempotency_conflict", Problem.IDEMPOTENCY_CONFLICT),
                    Triple(412, "registration_changed", Problem.REGISTRATION_CHANGED),
                    Triple(409, "registration_changed", Problem.RESPONSE),
                    Triple(412, "idempotency_conflict", Problem.RESPONSE),
                    Triple(401, "PRIVATE", Problem.AUTHENTICATION),
                    Triple(403, "PRIVATE", Problem.PERMISSION),
                    Triple(404, "PRIVATE", Problem.RESPONSE),
                    Triple(503, "PRIVATE", Problem.NETWORK),
                    Triple(307, "PRIVATE", Problem.REDIRECT),
                )
                for ((status, category, expected) in cases) {
                    server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/leak"))
                        .setBody("""{"error":"$category","message":"PRIVATE_token PRIVATE_credential"}"""))
                    try { backend.deletePush(connection, PushMutation(1, "a".repeat(32))); fail("Failure accepted") }
                    catch (failure: InboxFailure) {
                        assertEquals(expected, failure.problem)
                        assertFalse(failure.message.orEmpty().contains("PRIVATE"))
                    }
                }
                assertEquals(cases.size, server.requestCount)
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test fun malformedRegistrationRepresentationsAndEtagsFailClosed() = runTest {
        MockWebServer().use { server ->
            server.useHttps(serverTrust.sslSocketFactory(), false)
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
            try {
                val backend = HttpBackend(http)
                val connection = Connection.create("Home", server.url("/").toString(), "PRIVATE_credential")
                for ((etag, body) in listOf(
                    null to disabled, "0" to disabled, "W/\"0\"" to disabled, "\"00\"" to disabled,
                    "\"-1\"" to disabled, "\"9223372036854775808\"" to disabled,
                    "\"1\"" to active.replace("true", "\"true\""),
                    "\"1\"" to active.replace("PRIVATE_cursor", " "),
                    "\"1\"" to active.replace("fcm", "unknown"),
                    "\"1\"" to active.replace("true", "false"),
                    "\"0\"" to disabled.replace("\"enabled\":false,", ""),
                    "\"0\"" to disabled.dropLast(1).plus(",\"destination\":\"PRIVATE_token\"}"),
                )) {
                    server.enqueue(MockResponse().setBody(body).apply { etag?.let { setHeader("ETag", it) } })
                    try { backend.pushRegistration(connection); fail("Malformed registration accepted") }
                    catch (failure: InboxFailure) {
                        assertEquals(Problem.RESPONSE, failure.problem)
                        assertFalse(failure.message.orEmpty().contains("PRIVATE"))
                    }
                }
                server.enqueue(MockResponse().addHeader("ETag", "\"0\"").addHeader("ETag", "\"0\"").setBody(disabled))
                try { backend.pushRegistration(connection); fail("Repeated ETag accepted") }
                catch (failure: InboxFailure) { assertEquals(Problem.RESPONSE, failure.problem) }
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }
}
