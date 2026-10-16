package no.varde.app.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxTest {
    private val connection = Connection.create("Home", "https://example.com", "token")
    private val row = AlertRow("alert-1", "2026-01-01T12:00:00Z", "Disk pressure", "high", "2026-01-01T14:00:00+02:00", Presentation("pending", 0))

    @Test fun opensInboxAndDetailsWithoutRefreshingOnReturn() = runTest {
        val backend = FakeBackend(row)
        val inbox = Inbox(this, MemorySettings(connection), backend)
        runCurrent()
        assertEquals(listOf(row), inbox.state.value.items)
        assertEquals(1, backend.historyCalls)
        inbox.open(row.id)
        runCurrent()
        assertEquals("Original summary", inbox.state.value.detail?.alert?.summary)
        inbox.closeDetail()
        runCurrent()
        assertEquals(1, backend.historyCalls)
        inbox.refresh()
        runCurrent()
        assertEquals(2, backend.historyCalls)
    }

    @Test fun failedRefreshRetainsDisplayedContentAsStale() = runTest {
        val backend = FakeBackend(row)
        val inbox = Inbox(this, MemorySettings(connection), backend)
        runCurrent()
        backend.problem = Problem.AUTHENTICATION
        inbox.refresh()
        runCurrent()
        assertEquals(listOf(row), inbox.state.value.items)
        assertTrue(inbox.state.value.stale)
        assertEquals(Problem.AUTHENTICATION, inbox.state.value.problem)
        assertSame(connection, inbox.state.value.connection)
    }

    @Test fun refreshingInboxDoesNotStrandAnInFlightDetail() = runTest {
        val completed = CompletableDeferred<AlertDetail>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection) = listOf(row)
            override suspend fun read(connection: Connection, id: String) = completed.await()
        }
        val inbox = Inbox(this, MemorySettings(connection), backend)
        runCurrent()
        inbox.open(row.id)
        runCurrent()
        inbox.refresh()
        runCurrent()
        completed.complete(AlertDetail(row, "Retained original"))
        runCurrent()
        assertFalse(inbox.state.value.detail!!.loading)
        assertEquals("Retained original", inbox.state.value.detail?.alert?.summary)
    }

    @Test fun changingConfigurationIgnoresObsoleteNetworkResponses() = runTest {
        val delayed = CompletableDeferred<List<AlertRow>>()
        val settings = MemorySettings(connection)
        val backend = object : Backend {
            override suspend fun history(connection: Connection): List<AlertRow> =
                if (connection.name == "Home") withContext(NonCancellable) { delayed.await() } else emptyList()
            override suspend fun read(connection: Connection, id: String) = AlertDetail(row, "old")
        }
        val inbox = Inbox(this, settings, backend)
        runCurrent()
        inbox.save("Replacement", "https://new.example.com", "new-token") {}
        runCurrent()
        delayed.complete(listOf(row))
        runCurrent()
        assertEquals("Replacement", inbox.state.value.connection?.name)
        assertEquals(connection.id, inbox.state.value.connection?.id)
        assertTrue(inbox.state.value.items.isEmpty())
        val recreated = Inbox(this, settings, backend)
        runCurrent()
        assertEquals("Replacement", recreated.state.value.connection?.name)
        assertTrue(recreated.state.value.items.isEmpty())
    }

    private class MemorySettings(var connection: Connection?) : ConnectionSettings {
        override suspend fun load() = connection
        override suspend fun save(connection: Connection) { this.connection = connection }
    }

    private class FakeBackend(val row: AlertRow) : Backend {
        var historyCalls = 0
        var problem: Problem? = null
        override suspend fun history(connection: Connection): List<AlertRow> {
            historyCalls++
            problem?.let { throw InboxFailure(it) }
            return listOf(row)
        }
        override suspend fun read(connection: Connection, id: String) = AlertDetail(row, "Original summary")
    }
}
