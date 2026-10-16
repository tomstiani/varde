package no.varde.app.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagementTest {
    private val home = Connection.create("Home", "https://home.example.com", "home-token", "home")
    private val work = Connection.create("Work", "https://work.example.com", "work-token", "work")
    private val row = AlertRow("shared-id", "2026-01-01T00:00:00Z", "Alert", "normal", "2020-01-01T00:00:00Z", Presentation("fallback", 1))

    @Test fun renamePreservesRowsDetailsAndCredentialWithoutFetching() = runTest {
        val settings = TestSettings(listOf(home, work))
        val backend = TestBackend()
        val inbox = Inbox(this, settings, backend)
        runCurrent()
        inbox.open(AlertKey(home.id, row.id))
        runCurrent()
        val rows = inbox.state.value.items
        val detail = inbox.state.value.detail
        inbox.save(home.id, "Renamed", home.baseUrl.toString(), "") {}
        runCurrent()
        assertEquals(rows, inbox.state.value.items)
        assertEquals(detail, inbox.state.value.detail)
        assertEquals("Renamed", inbox.state.value.connection(detail!!.key.connectionId)?.name)
        assertEquals(home.id, settings.connections.first().id)
        assertEquals(home.secret, settings.connections.first().secret)
        assertEquals(2, backend.historyCalls)
    }

    @Test fun duplicateNameComparisonUsesFullLocaleIndependentCaseFolding() = runTest {
        val street = Connection.create("Straße", "https://street.example.com", "token")
        val sigma = Connection.create("Σ", "https://sigma.example.com", "token")
        val inbox = Inbox(this, TestSettings(listOf(street, sigma)), TestBackend())
        runCurrent()
        inbox.save(null, " STRASSE ", "https://other.example.com", "token") {}
        runCurrent()
        assertEquals(Problem.DUPLICATE_NAME, inbox.state.value.settingsProblem)
        inbox.save(null, "ς", "https://other.example.com", "token") {}
        runCurrent()
        assertEquals(Problem.DUPLICATE_NAME, inbox.state.value.settingsProblem)
        assertEquals(2, inbox.state.value.connections.size)
        inbox.save(null, "I", "https://upper.example.com", "token") {}
        runCurrent()
        inbox.save(null, "ı", "https://dotless.example.com", "token") {}
        runCurrent()
        assertEquals(4, inbox.state.value.connections.size)
    }

    @Test fun removalClearsOnlyItsSourceAndIgnoresLateHistoryAndDetail() = runTest {
        val history = CompletableDeferred<HistoryPage>()
        val detail = CompletableDeferred<AlertDetail>()
        var hold = false
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage =
                if (hold && connection.id == home.id) withContext(NonCancellable) { history.await() } else HistoryPage(listOf(row))
            override suspend fun read(connection: Connection, id: String): AlertDetail = withContext(NonCancellable) { detail.await() }
        }
        val settings = TestSettings(listOf(home, work))
        val inbox = Inbox(this, settings, backend)
        runCurrent()
        val unrelated = inbox.state.value.items.single { it.key.connectionId == work.id }
        hold = true
        inbox.open(AlertKey(home.id, row.id))
        inbox.refresh()
        runCurrent()
        inbox.remove(home.id) {}
        runCurrent()
        assertEquals(listOf(unrelated), inbox.state.value.items)
        assertNull(inbox.state.value.detail)
        assertEquals(listOf(work), settings.connections)
        history.complete(HistoryPage(listOf(row)))
        detail.complete(AlertDetail(row, "obsolete"))
        runCurrent()
        assertEquals(listOf(unrelated), inbox.state.value.items)
        assertNull(inbox.state.value.detail)
    }

    @Test fun endpointChangeClearsSourceButPreservesUnrelatedContentDuringReload() = runTest {
        val changedPage = CompletableDeferred<HistoryPage>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage =
                if (connection.secret == "replacement") changedPage.await() else HistoryPage(listOf(row))
            override suspend fun read(connection: Connection, id: String) = AlertDetail(row, "original")
        }
        val inbox = Inbox(this, TestSettings(listOf(home, work)), backend)
        runCurrent()
        inbox.open(AlertKey(home.id, row.id))
        runCurrent()
        inbox.save(home.id, home.name, "https://new.example.com", "replacement") {}
        runCurrent()
        assertEquals(listOf(work.id), inbox.state.value.items.map { it.key.connectionId })
        assertNull(inbox.state.value.detail)
        assertEquals(home.id, inbox.state.value.connections.first().id)
        changedPage.complete(HistoryPage(emptyList()))
        runCurrent()
        assertEquals(listOf(work.id), inbox.state.value.items.map { it.key.connectionId })
    }

    @Test fun savingUnreachableConnectionSucceedsButFailedStorageDoesNotChangeState() = runTest {
        val settings = TestSettings(listOf(home))
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage = throw InboxFailure(Problem.NETWORK)
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, settings, backend)
        runCurrent()
        var saved = false
        inbox.save(null, work.name, work.baseUrl.toString(), work.secret) { saved = true }
        runCurrent()
        assertTrue(saved)
        assertEquals(2, inbox.state.value.connections.size)
        settings.fail = true
        inbox.remove(home.id) {}
        runCurrent()
        assertEquals(2, inbox.state.value.connections.size)
        assertEquals(Problem.SETTINGS, inbox.state.value.settingsProblem)
    }

    private inner class TestBackend : Backend {
        var historyCalls = 0
        override suspend fun history(connection: Connection, cursor: String?): HistoryPage { historyCalls++; return HistoryPage(listOf(row)) }
        override suspend fun read(connection: Connection, id: String) = AlertDetail(row, connection.name)
    }

    private class TestSettings(var connections: List<Connection>) : ConnectionSettings {
        var fail = false
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) {
            if (fail) throw InboxFailure(Problem.SETTINGS)
            this.connections = connections
        }
    }
}
