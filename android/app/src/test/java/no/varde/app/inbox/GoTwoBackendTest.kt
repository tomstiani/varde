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
import no.varde.app.notifications.*

class GoTwoBackendTest {
    @Test fun coordinatorCatchesUpRealSourcesIndependentlyWithoutArchiveOrBodyReads() = runBlocking {
        val backendDirectory=File(requireNotNull(System.getProperty("varde.backend.dir")))
        GoBackendFixture(backendDirectory).use { home ->
            GoBackendFixture(backendDirectory).use { work ->
                val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
                val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
                val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
                GoTlsProxy(home,certificate).use { homeProxy ->
                    GoTlsProxy(work,certificate).use { workProxy ->
                        val homeConnection=homeProxy.connection("Home",home.installationSecret,"home")
                        val workConnection=workProxy.connection("Work",work.installationSecret,"work")
                        val settings=Settings(listOf(homeConnection,workConnection))
                        val output=RecoveryOutput()
                        val directory=java.nio.file.Files.createTempDirectory("varde-recovery-").toFile()
                        try {
                            val transport=HttpBackend(client)
                            val coordinator=NotificationCoordinator(settings,transport,directory,output)
                            home.submit(0); work.submit(0)
                            coordinator.recoverAll()
                            assertTrue(output.notices.isEmpty())
                            for(number in 1..65) { home.submit(number); work.submit(number) }
                            coordinator.recoverAll()
                            assertEquals(mapOf("Home" to 65,"Work" to 65),output.notices.associate { it.source to it.count })
                            home.pause()
                            work.submit(1000)
                            coordinator.recoverAll()
                            assertEquals(Problem.NETWORK,coordinator.recoveryProblems.value["home"])
                            assertEquals("Work",output.notices.last().source)
                            assertEquals(1,output.notices.last().count)
                            home.resume()
                            val noticesBefore=output.notices.size
                            assertEquals(200,work.submit(1000).first)
                            NotificationCoordinator(settings,transport,directory,output).recoverAll()
                            assertEquals(noticesBefore,output.notices.size)
                            work.rotateInstallation()
                            coordinator.recoverAll()
                            assertEquals(Problem.AUTHENTICATION,coordinator.recoveryProblems.value["work"])
                            settings.save(listOf(homeConnection,workProxy.connection("Work",work.installationSecret,"work")))
                            coordinator.synchronize()
                            coordinator.recoverAll()
                            assertEquals(noticesBefore,output.notices.size)
                            assertTrue(coordinator.recoveryProblems.value.isEmpty())
                            assertTrue(homeProxy.pageSizes.isEmpty() && workProxy.pageSizes.isEmpty())
                            assertEquals(0,homeProxy.detailReads.get()+workProxy.detailReads.get())
                        } finally { directory.deleteRecursively() }
                    }
                }
            }
        }
    }

    private class RecoveryOutput:NotificationOutput {
        val notices=mutableListOf<CatchUpNotice>()
        override fun enabled()=true
        override fun show(notice:AlertNotice,interrupt:Boolean)=error("Unexpected individual notification")
        override fun catchUp(notice:CatchUpNotice,interrupt:Boolean) { assertTrue(interrupt); notices+=notice }
        override fun active(targetId:String)=notices.any { it.targetId==targetId }
        override fun cancel(targetId:String)=Unit
        override fun rename(targetId:String,source:String)=Unit
    }

    @Test fun recoversBoundedChangesThroughRealTransportAcrossRestartAndConcurrentArrivals() = runBlocking {
        val directory = File(requireNotNull(System.getProperty("varde.backend.dir")))
        GoBackendFixture(directory).use { home ->
            GoBackendFixture(directory).use { work ->
                home.submit(0)
                work.submit(0)
                val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
                val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
                val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
                GoTlsProxy(home,certificate).use { homeProxy ->
                    GoTlsProxy(work,certificate).use { workProxy ->
                        val transport=HttpBackend(client)
                        val homeConnection=homeProxy.connection("Home",home.installationSecret,"home")
                        val workConnection=workProxy.connection("Work",work.installationSecret,"work")
                        val checkpoint=transport.checkpoint(homeConnection)
                        val workCheckpoint=transport.checkpoint(workConnection)
                        val expected=(1..65).map { home.submit(it).second }.toSet()
                        val workAlert=work.submit(1).second
                        var page=transport.changes(homeConnection,checkpoint)
                        assertTrue(page.hasMore)
                        assertTrue(page.items.size<=50)
                        val late=home.submit(1000).second
                        home.restart()
                        val accepted=mutableSetOf<String>()
                        while (true) {
                            accepted += page.items.filter { it.kind=="accepted" }.map { it.alertId }
                            assertTrue(page.items.size<=50)
                            if (!page.hasMore) break
                            page=transport.changes(homeConnection,page.nextCursor)
                        }
                        assertEquals(expected,accepted)
                        val next=transport.changes(homeConnection,page.nextCursor)
                        assertTrue(next.items.any { it.kind=="accepted" && it.alertId==late })
                        val other=transport.changes(workConnection,workCheckpoint)
                        assertEquals(setOf(workAlert),other.items.filter { it.kind=="accepted" }.map { it.alertId }.toSet())
                        assertTrue(other.items.filter { it.kind=="accepted" }.all { it.revision==0 })
                        try { transport.changes(workConnection,checkpoint); fail("Cross-store cursor accepted") }
                        catch (failure:InboxFailure) { assertEquals(Problem.RECOVERY_CURSOR,failure.problem) }
                        work.rotateInstallation()
                        try { transport.changes(workConnection,other.nextCursor); fail("Revoked access accepted") }
                        catch (failure:InboxFailure) { assertEquals(Problem.AUTHENTICATION,failure.problem) }
                    }
                }
            }
        }
    }

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
