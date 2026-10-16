package no.varde.app.inbox

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class BackendTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val testTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    @Test fun readsBoundedHistoryAndFullOriginalOverHttps() = runTest {
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val backend = HttpBackend(OkHttpClient.Builder().sslSocketFactory(testTrust.sslSocketFactory(), testTrust.trustManager).build())
            val connection = Connection.create("Lab", server.url("/prefix/").toString(), "private-token")
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[{"id":"one","acceptedAt":"2026-01-01T12:00:00Z","title":"Disk pressure","priority":"high","observedAt":"2026-01-01T14:00:00+02:00","presentation":{"state":"pending","revision":0}}],"nextCursor":null}"""))
            val rows = backend.history(connection)
            assertEquals("Disk pressure", rows.items.single().title)
            assertNull(rows.nextCursor)
            val request = server.takeRequest()
            assertEquals("/prefix/v1/alerts?order=acceptedAt&limit=50", request.path)
            assertEquals("Bearer private-token", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"id":"one","acceptedAt":"2026-01-01T12:00:00Z","original":{"schemaVersion":1,"eventId":"e","title":"Disk pressure","summary":"","priority":"high","observedAt":"2026-01-01T14:00:00+02:00","data":[]},"presentation":{"state":"fallback","revision":1}}"""))
            val detail = backend.read(connection, "one")
            assertEquals("", detail.summary)
            assertEquals(1, detail.row.presentation.revision)
            assertEquals("/prefix/v1/alerts/one", server.takeRequest().path)
        }
    }

    @Test fun continuesWithTheOpaqueCursorAndUnchangedPageOptions() = runTest {
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val backend = HttpBackend(OkHttpClient.Builder().sslSocketFactory(testTrust.sslSocketFactory(), testTrust.trustManager).build())
            val connection = Connection.create("Lab", server.url("/").toString(), "private-token")
            val token = "opaque+ /?token"
            server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))
            backend.history(connection, token)
            val request = server.takeRequest()
            assertEquals(token, request.requestUrl!!.queryParameter("cursor"))
            assertEquals("50", request.requestUrl!!.queryParameter("limit"))
            assertEquals("acceptedAt", request.requestUrl!!.queryParameter("order"))
            assertEquals("Bearer private-token", request.getHeader("Authorization"))
        }
    }

    @Test fun refusesRedirectsAndNeverEchoesServerErrors() = runTest {
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val backend = HttpBackend(OkHttpClient.Builder().sslSocketFactory(testTrust.sslSocketFactory(), testTrust.trustManager).build())
            val connection = Connection.create("Lab", server.url("/").toString(), "private-token")
            for ((status, problem) in listOf(302 to Problem.REDIRECT, 401 to Problem.AUTHENTICATION, 403 to Problem.PERMISSION, 404 to Problem.NOT_FOUND, 503 to Problem.NETWORK, 200 to Problem.RESPONSE)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/leak")).setBody("PRIVATE server error"))
                try { backend.history(connection); fail("Failure accepted") } catch (failure: InboxFailure) {
                    assertEquals(problem, failure.problem)
                    assertFalse(failure.message.orEmpty().contains("PRIVATE"))
                }
            }
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun defaultClientRejectsAnUntrustedCertificate() = runTest {
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val connection = Connection.create("Lab", server.url("/").toString(), "private-token")
            try { HttpBackend().history(connection); fail("Untrusted certificate accepted") } catch (failure: InboxFailure) {
                assertEquals(Problem.NETWORK, failure.problem)
            }
            assertEquals(0, server.requestCount)
        }
    }
}
