package no.varde.app.inbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

class GoHistoryTest {
    @Test fun realSqliteSnapshotExcludesArrivalsAndResumesAfterRestart() = runBlocking {
        val backendDirectory = File(requireNotNull(System.getProperty("varde.backend.dir")) { "Run the goHistoryTest Gradle task" })
        GoBackendFixture(backendDirectory).use { fixture ->
            val originals = (0 until 120).map { fixture.submit(it).second }
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            MockWebServer().use { proxy ->
                proxy.useHttps(serverTrust.sslSocketFactory(), false)
                proxy.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val received = request.requestUrl!!
                        val upstream = fixture.address.newBuilder().encodedPath(received.encodedPath).encodedQuery(received.encodedQuery).build()
                        val forwarded = Request.Builder().url(upstream)
                            .header("Authorization", request.getHeader("Authorization").orEmpty()).build()
                        return fixture.http.newCall(forwarded).execute().use { response ->
                            MockResponse().setResponseCode(response.code).setHeader("Content-Type", "application/json").setBody(response.body!!.string())
                        }
                    }
                }
                proxy.start()
                // Trust is restricted to this test's local TLS proxy; the release app is unchanged.
                val http = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
                val connection = Connection.create("Real backend", proxy.url("/").toString(), fixture.installationSecret)
                val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                val lifetime = SupervisorJob()
                val scope = CoroutineScope(lifetime + dispatcher)
                try {
                    val inbox = withContext(dispatcher) { Inbox(scope, ReadOnlySettings(connection), HttpBackend(http)) }
                    withTimeout(30_000) { inbox.state.first { !it.loading && it.items.size == 50 } }
                    val detailKey = inbox.state.value.items.first().key
                    withContext(dispatcher) { inbox.open(detailKey) }
                    withTimeout(15_000) { inbox.state.first { it.detail?.loading == false } }
                    val detail = inbox.state.value.detail
                    val firstLate = fixture.submit(1000).second
                    val duplicate = fixture.submit(0)
                    assertEquals(200, duplicate.first)
                    assertEquals(originals.first(), duplicate.second)
                    fixture.restart()
                    val concurrent = async(Dispatchers.IO) { fixture.submit(1001).second }
                    withContext(dispatcher) { inbox.loadOlder() }
                    withTimeout(30_000) { inbox.state.first { !it.loadingOlder && it.items.size == 100 } }
                    val secondLate = concurrent.await()
                    withContext(dispatcher) { inbox.loadOlder() }
                    withTimeout(30_000) { inbox.state.first { !it.loadingOlder && it.historyComplete } }
                    assertEquals(120, inbox.state.value.items.size)
                    assertEquals(originals.toSet(), inbox.state.value.items.map { it.key.alertId }.toSet())
                    assertFalse(inbox.state.value.items.any { it.key.alertId == firstLate || it.key.alertId == secondLate })
                    assertEquals(detail, inbox.state.value.detail)
                    assertTrue(inbox.state.value.problems.isEmpty())
                    withContext(dispatcher) { inbox.refresh() }
                    withTimeout(30_000) { inbox.state.first { !it.loading && it.items.size == 50 } }
                    while (inbox.state.value.hasMore) {
                        withContext(dispatcher) { inbox.loadOlder() }
                        withTimeout(30_000) { inbox.state.first { !it.loadingOlder } }
                    }
                    assertEquals(122, inbox.state.value.items.size)
                    assertEquals((originals + firstLate + secondLate).toSet(), inbox.state.value.items.map { it.key.alertId }.toSet())
                    assertTrue(inbox.state.value.historyComplete)
                    assertTrue(inbox.state.value.problems.isEmpty())
                } finally {
                    lifetime.cancelAndJoin()
                    dispatcher.close()
                    http.dispatcher.executorService.shutdown()
                    http.connectionPool.evictAll()
                }
            }
        }
    }

    private class ReadOnlySettings(private val connection: Connection) : ConnectionSettings {
        override suspend fun load() = listOf(connection)
        override suspend fun save(connections: List<Connection>) = error("No settings changes expected")
    }
}
