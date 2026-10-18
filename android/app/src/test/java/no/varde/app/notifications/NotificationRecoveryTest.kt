package no.varde.app.notifications

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import no.varde.app.inbox.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val home = Connection.create("Home", "https://home.example", "secret", "home")
    private val first = "alert_" + "a".repeat(32)
    private val second = "alert_" + "b".repeat(32)

    @Test fun initializesSilentlyThenSummarizesOnlyMissedAcceptancesAcrossRestart() = runBlocking {
        val settings = Settings(listOf(home))
        val backend = RecoveryBackend()
        val output = Output()
        val directory = temporary.newFolder()
        val coordinator = NotificationCoordinator(settings, backend, directory, output)
        coordinator.recover("home")
        assertTrue(output.summaries.isEmpty())
        backend.pages["baseline"] = ChangePage(listOf(ChangeEntry(first,"accepted",0), ChangeEntry(first,"presentation",1), ChangeEntry(second,"accepted",0)), "after", false)
        coordinator.recover("home")
        assertEquals(2, output.summaries.single().first.count)
        assertEquals("Home",output.summaries.single().first.source)
        assertTrue(output.summaries.single().second)
        val registration = coordinator.currentRegistration("home")!!
        coordinator.receive(AlertSignal("1", registration, first))
        assertTrue(output.individuals.isEmpty())
        NotificationCoordinator(settings,backend,directory,output).recover("home")
        assertEquals(1,output.summaries.size)
    }

    @Test fun boundedPartialPassResumesAfterRestartAndAnnouncesOnceAtCompletion() = runBlocking {
        val settings=Settings(listOf(home))
        val backend=RecoveryBackend()
        val output=Output()
        val directory=temporary.newFolder()
        val coordinator=NotificationCoordinator(settings,backend,directory,output)
        coordinator.recover("home")
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0)),"middle",true)
        backend.pages["middle"]=ChangePage(listOf(ChangeEntry(second,"accepted",0)),"done",false)
        coordinator.recover("home",maxPages=1)
        assertEquals(Problem.RECOVERY_PENDING, coordinator.recoveryProblems.value["home"])
        assertTrue(output.summaries.isEmpty())
        coordinator.receive(AlertSignal("1",coordinator.currentRegistration("home")!!,first))
        assertTrue(output.individuals.isEmpty())
        val restarted=NotificationCoordinator(settings,backend,directory,output)
        restarted.recover("home")
        assertEquals(2,output.summaries.single().first.count)
        restarted.recover("home")
        assertEquals(1,output.summaries.size)
    }

    @Test fun failedSourceDoesNotBlockHealthyCatchUpAndKeepsItsPositionForRetry() = runBlocking {
        val settings=Settings(listOf(home,Connection.create("Cabin","https://cabin.example","other","cabin")))
        val backend=RecoveryBackend()
        val output=Output()
        val coordinator=NotificationCoordinator(settings,backend,temporary.newFolder(),output)
        coordinator.recoverAll()
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0)),"done",false)
        backend.problems["home"]=Problem.NETWORK
        coordinator.recoverAll()
        assertEquals("Cabin",output.summaries.single().first.source)
        assertEquals(Problem.NETWORK,coordinator.recoveryProblems.value["home"])
        assertNull(coordinator.recoveryProblems.value["cabin"])
        backend.problems.clear()
        coordinator.recoverAll()
        assertEquals(setOf("Cabin","Home"),output.summaries.map { it.first.source }.toSet())
        assertTrue(coordinator.recoveryProblems.value.isEmpty())
    }

    @Test fun invalidCursorDiscardsPartialCountAndEstablishesANewSilentBaseline() = runBlocking {
        val backend=RecoveryBackend()
        val output=Output()
        val coordinator=NotificationCoordinator(Settings(listOf(home)),backend,temporary.newFolder(),output)
        coordinator.recover("home")
        val previous=coordinator.currentRegistration("home")!!
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0)),"partial",true)
        coordinator.recover("home",maxPages=1)
        backend.problems["home"]=Problem.RECOVERY_CURSOR
        coordinator.recover("home")
        assertEquals(Problem.RECOVERY_CURSOR,coordinator.recoveryProblems.value["home"])
        backend.problems.clear()
        backend.checkpointCursor="fresh"
        coordinator.recover("home")
        assertNotEquals(previous,coordinator.currentRegistration("home"))
        coordinator.receive(AlertSignal("1",previous,second))
        assertTrue(output.individuals.isEmpty())
        assertTrue(output.summaries.isEmpty())
        backend.pages["fresh"]=ChangePage(listOf(ChangeEntry(second,"accepted",0)),"after-fresh",false)
        coordinator.recover("home")
        assertEquals(1,output.summaries.single().first.count)
    }

    @Test fun catchUpTapTargetsItsConnectionInboxAndAccessChangesCancelIt() = runBlocking {
        val settings=Settings(listOf(home))
        val backend=RecoveryBackend()
        val output=Output()
        val coordinator=NotificationCoordinator(settings,backend,temporary.newFolder(),output)
        coordinator.recover("home")
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0)),"done",false)
        coordinator.recover("home")
        val target=output.summaries.single().first.targetId
        val destination=coordinator.resolveTap(target)
        assertNotNull(destination)
        assertNull(destination!!.key)
        assertEquals("home",destination.connection.id)
        assertFalse(output.active(target))
        settings.connections=listOf(Connection.create("Replacement","https://other.example","other","home"))
        coordinator.synchronize()
        assertNull(coordinator.resolveTap(target))
    }

    @Test fun pushRacingWithRecoveryIsExcludedAndLateCoveredPushRemainsSilent() = runBlocking {
        val backend=RecoveryBackend().apply { allowBodyRead=true }
        val output=Output()
        val coordinator=NotificationCoordinator(Settings(listOf(home)),backend,temporary.newFolder(),output)
        coordinator.recover("home")
        val registration=coordinator.currentRegistration("home")!!
        backend.gate=CompletableDeferred()
        val recovering=async { coordinator.recover("home") }
        withTimeout(5_000) { backend.requested.await() }
        coordinator.receive(AlertSignal("1",registration,first))
        backend.gate!!.complete(ChangePage(listOf(ChangeEntry(first,"accepted",0),ChangeEntry(second,"accepted",0)),"done",false))
        recovering.await()
        assertEquals(1,output.summaries.single().first.count)
        coordinator.receive(AlertSignal("1",registration,second))
        assertEquals(1,output.interruptions.size)
    }

    @Test fun canceledPageDoesNotConsumeProgressOrLeaveTheConnectionLocked() = runBlocking {
        val backend=RecoveryBackend()
        val output=Output()
        val coordinator=NotificationCoordinator(Settings(listOf(home)),backend,temporary.newFolder(),output)
        coordinator.recover("home")
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0)),"partial",true)
        backend.pages["partial"]=ChangePage(listOf(ChangeEntry(second,"accepted",0)),"done",false)
        coordinator.recover("home",maxPages=1)
        backend.gate=CompletableDeferred()
        val canceled=async { coordinator.recover("home") }
        withTimeout(5_000) { backend.requested.await() }
        canceled.cancelAndJoin()
        backend.gate=null
        coordinator.recover("home")
        assertEquals(2,output.summaries.single().first.count)
    }

    @Test fun permissionDenialAndUncertainCatchUpOutputDoNotCreateAReplayBacklog() = runBlocking {
        val backend=RecoveryBackend()
        val output=Output().apply { allowed=false }
        val directory=temporary.newFolder()
        val settings=Settings(listOf(home))
        val coordinator=NotificationCoordinator(settings,backend,directory,output)
        coordinator.recover("home")
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0)),"after-muted",false)
        coordinator.recover("home")
        assertTrue(output.summaries.isEmpty())
        output.allowed=true
        output.failCatchUp=true
        backend.pages["after-muted"]=ChangePage(listOf(ChangeEntry(second,"accepted",0)),"done",false)
        coordinator.recover("home")
        assertEquals(Problem.SETTINGS,coordinator.recoveryProblems.value["home"])
        assertEquals(1,output.summaries.single().first.count)
        NotificationCoordinator(settings,backend,directory,output).recover("home")
        assertEquals(1,output.summaries.size)
    }

    @Test fun renameUpdatesCatchUpSilentlyAndRemovalCancelsItsNavigationTarget() = runBlocking {
        val settings=Settings(listOf(home))
        val backend=RecoveryBackend()
        val output=Output()
        val coordinator=NotificationCoordinator(settings,backend,temporary.newFolder(),output)
        coordinator.recover("home")
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0)),"done",false)
        coordinator.recover("home")
        val target=output.summaries.single().first.targetId
        settings.connections=listOf(Connection.create("Renamed","https://home.example","secret","home"))
        coordinator.synchronize()
        assertEquals("Renamed",output.summaries.last().first.source)
        assertFalse(output.summaries.last().second)
        settings.connections=emptyList()
        coordinator.synchronize()
        assertFalse(output.active(target))
        assertNull(coordinator.resolveTap(target))
    }

    @Test fun obsoleteRecoveryFailureCannotResetReplacementAccessOrItsSession() = runBlocking {
        val settings=Settings(listOf(home))
        val backend=RecoveryBackend()
        val coordinator=NotificationCoordinator(settings,backend,temporary.newFolder(),Output())
        coordinator.recover("home")
        backend.gate=CompletableDeferred()
        val old=async { coordinator.recover("home") }
        withTimeout(5_000) { backend.requested.await() }
        settings.connections=listOf(Connection.create("Replacement","https://other.example","other","home"))
        coordinator.synchronize()
        val replacement="d".repeat(32)
        coordinator.activate("home",replacement)
        backend.gate!!.completeExceptionally(InboxFailure(Problem.RECOVERY_CURSOR))
        old.await()
        assertEquals(replacement,coordinator.currentRegistration("home"))
        assertTrue(coordinator.recoveryProblems.value.isEmpty())
    }

    @Test fun legacyNotificationJournalUpgradesWithoutReannouncingAnIndividualAlert() = runBlocking {
        val directory=temporary.newFolder()
        val registration="c".repeat(32)
        val target="e".repeat(32)
        java.io.File(directory,"metadata.json").writeText("""{"version":1,"sessions":[{"connection":"home","registration":"$registration","access":"370119697e83f8b4da21835b9bded3c00a01b79bb102e06073296226f7090bfe","retired":false}],"entries":[{"registration":"$registration","alert":"$first","target":"$target","dismissed":false}]}""")
        val output=Output()
        output.show(AlertNotice(target,"Home"),false)
        val backend=RecoveryBackend().apply { allowBodyRead=true }
        val coordinator=NotificationCoordinator(Settings(listOf(home)),backend,directory,output)
        coordinator.receive(AlertSignal("1",registration,first))
        assertEquals("Summary",output.individuals.last().detail?.summary)
        assertTrue(output.interruptions.isEmpty())
        coordinator.recover("home")
        backend.pages["baseline"]=ChangePage(listOf(ChangeEntry(first,"accepted",0),ChangeEntry(second,"accepted",0)),"done",false)
        coordinator.recover("home")
        assertEquals(1,output.summaries.single().first.count)
    }

    @Test fun corruptRecoveryCountsFailClosedWithoutAdvancingOrNotifying() = runBlocking {
        val directory=temporary.newFolder()
        java.io.File(directory,"metadata.json").writeText("""{"version":2,"sessions":[{"connection":"home","registration":"${"c".repeat(32)}","access":"370119697e83f8b4da21835b9bded3c00a01b79bb102e06073296226f7090bfe","retired":false,"cursor":"baseline","pendingCount":-1,"summaryTarget":null,"summaryCount":0,"summaryDismissed":false}],"entries":[]}""")
        val output=Output()
        val coordinator=NotificationCoordinator(Settings(listOf(home)),RecoveryBackend(),directory,output)
        coordinator.recover("home")
        assertEquals(Problem.SETTINGS,coordinator.recoveryProblems.value["home"])
        assertTrue(output.summaries.isEmpty())
    }

    private class Settings(var connections: List<Connection>): ConnectionSettings {
        override suspend fun load()=connections
        override suspend fun save(connections: List<Connection>) { this.connections=connections }
    }
    private class RecoveryBackend: Backend {
        val pages=mutableMapOf<String,ChangePage>()
        val problems=mutableMapOf<String,Problem>()
        var checkpointCursor="baseline"
        var gate:CompletableDeferred<ChangePage>?=null
        val requested=CompletableDeferred<Unit>()
        var allowBodyRead=false
        override suspend fun checkpoint(connection: Connection): String {
            problems[connection.id]?.let { throw InboxFailure(it) }
            return checkpointCursor
        }
        override suspend fun changes(connection: Connection,cursor:String): ChangePage {
            problems[connection.id]?.let { throw InboxFailure(it) }
            gate?.let { requested.complete(Unit); return it.await() }
            return pages[cursor] ?: ChangePage(emptyList(),cursor,false)
        }
        override suspend fun history(connection: Connection,cursor:String?):HistoryPage=error("Recovery must not browse history")
        override suspend fun read(connection: Connection,id:String):AlertDetail {
            check(allowBodyRead) { "Recovery must not prefetch originals" }
            return AlertDetail(AlertRow(id,"2026-01-01T00:00:00Z","Original","high","2026-01-01T00:00:00Z",Presentation("fallback",1)),"Summary")
        }
    }
    private class Output: NotificationOutput {
        val summaries=mutableListOf<Pair<CatchUpNotice,Boolean>>()
        val individuals=mutableListOf<AlertNotice>()
        private val visible=mutableSetOf<String>()
        val interruptions=mutableListOf<AlertNotice>()
        var allowed=true
        var failCatchUp=false
        override fun enabled()=allowed
        override fun show(notice:AlertNotice,interrupt:Boolean) { individuals+=notice; visible+=notice.targetId; if(interrupt) interruptions+=notice }
        override fun catchUp(notice:CatchUpNotice,interrupt:Boolean) {
            summaries+=notice to interrupt; visible+=notice.targetId
            if(failCatchUp) { failCatchUp=false; error("Uncertain OS catch-up output") }
        }
        override fun active(targetId:String)=targetId in visible
        override fun cancel(targetId:String) { visible-=targetId }
        override fun rename(targetId:String,source:String) {
            summaries.lastOrNull { it.first.targetId==targetId }?.let { catchUp(it.first.copy(source=source),false) }
        }
    }
}
