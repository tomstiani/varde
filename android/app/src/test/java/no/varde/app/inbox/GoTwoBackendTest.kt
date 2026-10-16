package no.varde.app.inbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

class GoTwoBackendTest {
    @Test fun mergesRealBackendsAndRecoversFromOutageAndCredentialReplacement() = runBlocking {
        val directory = File(requireNotNull(System.getProperty("varde.backend.dir")))
        GoBackendFixture(directory).use { home ->
            GoBackendFixture(directory).use { work ->
                val expected = mutableSetOf<AlertKey>()
                for (number in 0 until 65) {
                    expected += AlertKey("home", home.submit(number).second)
                    expected += AlertKey("work", work.submit(number).second)
                }
                val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
                val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
                val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
                GoTlsProxy(home, certificate).use { homeProxy ->
                    GoTlsProxy(work, certificate).use { workProxy ->
                        val homeConnection = homeProxy.connection("Home", home.installationSecret, "home")
                        val workConnection = workProxy.connection("Work", work.installationSecret, "work")
                        val settings = Settings(listOf(homeConnection, workConnection))
                        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                        val lifetime = SupervisorJob()
                        val scope = CoroutineScope(lifetime + dispatcher)
                        try {
                            val transport = HttpBackend(client)
                            val inbox = withContext(dispatcher) { Inbox(scope, settings, transport) }
                            suspend fun settled() = withTimeout(30_000) { inbox.state.first { it.initialized && !it.loading && !it.loadingOlder && !it.saving && it.items.isNotEmpty() } }
                            suspend fun loadAll() {
                                while (inbox.state.value.hasMore) {
                                    val previousSize = inbox.state.value.items.size
                                    withContext(dispatcher) { inbox.loadOlder() }
                                    settled()
                                    val added = inbox.state.value.items.size - previousSize
                                    assertTrue("Load older must add between 1 and 50 rows", added in 1..50)
                                }
                            }
                            withTimeout(30_000) { inbox.state.first { !it.loading && it.items.size == 50 } }
                            assertEquals(setOf("home", "work"), inbox.state.value.items.map { it.key.connectionId }.toSet())
                            loadAll()
                            assertEquals(130, inbox.state.value.items.size)
                            assertEquals(expected, inbox.state.value.items.map { it.key }.toSet())
                            for ((newer, older) in inbox.state.value.items.zipWithNext()) {
                                assertTrue(Instant.parse(newer.row.acceptedAt) >= Instant.parse(older.row.acceptedAt))
                            }
                            assertTrue(homeProxy.pageSizes.size >= 2 && workProxy.pageSizes.size >= 2)
                            assertTrue((homeProxy.pageSizes + workProxy.pageSizes).all { it in 1..50 })
                            val homeKey = inbox.state.value.items.first { it.key.connectionId == "home" }.key
                            withContext(dispatcher) { inbox.open(homeKey) }
                            withTimeout(15_000) { inbox.state.first { it.detail?.loading == false } }
                            val retained = inbox.state.value.detail?.alert
                            assertNotNull(retained)
                            home.pause()
                            withContext(dispatcher) { inbox.refresh() }
                            settled()
                            assertEquals(Problem.NETWORK, inbox.state.value.problems["home"])
                            assertEquals(retained, inbox.state.value.detail?.alert)
                            assertTrue(inbox.state.value.detail!!.stale)
                            loadAll()
                            assertEquals(expected, inbox.state.value.items.map { it.key }.toSet())
                            assertFalse(inbox.state.value.historyComplete)
                            home.resume()
                            val workPagesBeforeRecovery = workProxy.pageSizes.size
                            withContext(dispatcher) { inbox.retryConnection("home") }
                            settled()
                            loadAll()
                            assertTrue(inbox.state.value.problems.isEmpty())
                            assertTrue(inbox.state.value.historyComplete)
                            assertTrue(workProxy.pageSizes.size > workPagesBeforeRecovery)
                            val homePagesBeforeRename = homeProxy.pageSizes.size
                            withContext(dispatcher) { inbox.save("home", "Renamed home", homeConnection.baseUrl.toString(), "") {} }
                            withTimeout(15_000) { inbox.state.first { !it.saving && it.connection("home")?.name == "Renamed home" } }
                            assertEquals(homePagesBeforeRename, homeProxy.pageSizes.size)
                            home.rotateInstallation()
                            withContext(dispatcher) { inbox.refresh() }
                            settled()
                            assertEquals(Problem.AUTHENTICATION, inbox.state.value.problems["home"])
                            val beforeReplacement = inbox.state.value.refreshRevision
                            withContext(dispatcher) { inbox.save("home", "Renamed home", homeConnection.baseUrl.toString(), home.installationSecret) {} }
                            withTimeout(30_000) { inbox.state.first { !it.loading && !it.saving && it.refreshRevision > beforeReplacement } }
                            loadAll()
                            assertEquals(expected, inbox.state.value.items.map { it.key }.toSet())
                            assertTrue(inbox.state.value.historyComplete)
                            val beforeRemoval = inbox.state.value.refreshRevision
                            withContext(dispatcher) { inbox.remove("home") {} }
                            withTimeout(30_000) { inbox.state.first { !it.loading && !it.saving && it.refreshRevision > beforeRemoval } }
                            loadAll()
                            assertEquals(65, inbox.state.value.items.size)
                            assertEquals(setOf("work"), inbox.state.value.items.map { it.key.connectionId }.toSet())
                            assertEquals(200, home.submit(0).first)
                        } finally {
                            lifetime.cancelAndJoin()
                            dispatcher.close()
                            client.dispatcher.executorService.shutdown()
                            client.connectionPool.evictAll()
                        }
                    }
                }
            }
        }
    }

    private class Settings(private var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }
}
