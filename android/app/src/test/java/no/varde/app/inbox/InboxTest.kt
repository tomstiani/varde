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
class InboxTest {
    private val connection = Connection.create("Home", "https://example.com", "token")
    private val row = AlertRow("alert-1", "2026-01-01T12:00:00Z", "Disk pressure", "high", "2026-01-01T14:00:00+02:00", Presentation("pending", 0))
    private val key = AlertKey(connection.id, row.id)

    @Test fun opensInboxAndDetailsWithoutRefreshingOnReturn() = runTest {
        val backend = FakeBackend(row)
        val inbox = Inbox(this, MemorySettings(listOf(connection)), backend)
        runCurrent()
        assertEquals(listOf(InboxItem(key, row)), inbox.state.value.items)
        assertEquals(1, backend.historyCalls)
        inbox.open(key)
        runCurrent()
        assertEquals("Original summary", inbox.state.value.detail?.alert?.summary)
        inbox.closeDetail()
        runCurrent()
        assertEquals(1, backend.historyCalls)
        inbox.refresh()
        runCurrent()
        assertEquals(2, backend.historyCalls)
    }

    @Test fun notificationOpensAnUnloadedAlertAndOffersRetryWithoutInventingContent() = runTest {
        var unavailable = true
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
            override suspend fun read(connection: Connection, id: String): AlertDetail {
                if (unavailable) throw InboxFailure(Problem.NETWORK)
                return AlertDetail(row, "Original summary")
            }
        }
        val inbox = Inbox(this, MemorySettings(listOf(connection)), backend)
        runCurrent()
        assertTrue(inbox.openNotification(key, connection))
        runCurrent()
        assertEquals(key, inbox.state.value.detail?.key)
        assertNull(inbox.state.value.detail?.alert)
        assertEquals(Problem.NETWORK, inbox.state.value.detail?.problem)
        unavailable = false
        inbox.retryDetail()
        runCurrent()
        assertEquals("Original summary", inbox.state.value.detail?.alert?.summary)
        val replacement = Connection.create("Other", "https://other.example", "other-token", connection.id)
        assertFalse(inbox.openNotification(key, replacement))
    }

    @Test fun failedRefreshRetainsDisplayedContentAsStale() = runTest {
        val backend = FakeBackend(row)
        val inbox = Inbox(this, MemorySettings(listOf(connection)), backend)
        runCurrent()
        backend.problem = Problem.AUTHENTICATION
        inbox.refresh()
        runCurrent()
        assertEquals(listOf(InboxItem(key, row)), inbox.state.value.items)
        assertTrue(connection.id in inbox.state.value.staleConnections)
        assertEquals(Problem.AUTHENTICATION, inbox.state.value.problems[connection.id])
        assertSame(connection, inbox.state.value.connections.single())
    }

    @Test fun refreshingInboxDoesNotStrandAnInFlightDetail() = runTest {
        val completed = CompletableDeferred<AlertDetail>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(listOf(row))
            override suspend fun read(connection: Connection, id: String) = completed.await()
        }
        val inbox = Inbox(this, MemorySettings(listOf(connection)), backend)
        runCurrent()
        inbox.open(key)
        runCurrent()
        inbox.refresh()
        runCurrent()
        completed.complete(AlertDetail(row, "Retained original"))
        runCurrent()
        assertFalse(inbox.state.value.detail!!.loading)
        assertEquals("Retained original", inbox.state.value.detail?.alert?.summary)
    }

    @Test fun changingConfigurationIgnoresObsoleteNetworkResponses() = runTest {
        val delayed = CompletableDeferred<HistoryPage>()
        val settings = MemorySettings(listOf(connection))
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage =
                if (connection.name == "Home") withContext(NonCancellable) { delayed.await() } else HistoryPage(emptyList())
            override suspend fun read(connection: Connection, id: String) = AlertDetail(row, "old")
        }
        val inbox = Inbox(this, settings, backend)
        runCurrent()
        inbox.save(connection.id, "Replacement", "https://new.example.com", "new-token") {}
        runCurrent()
        delayed.complete(HistoryPage(listOf(row)))
        runCurrent()
        assertEquals("Replacement", inbox.state.value.connections.single().name)
        assertEquals(connection.id, inbox.state.value.connections.single().id)
        assertTrue(inbox.state.value.items.isEmpty())
        val recreated = Inbox(this, settings, backend)
        runCurrent()
        assertEquals("Replacement", recreated.state.value.connections.single().name)
        assertTrue(recreated.state.value.items.isEmpty())
    }

    @Test fun savesMultipleConnectionsAndRejectsAmbiguousNamesOrUrls() = runTest {
        val settings = MemorySettings(listOf(connection))
        val inbox = Inbox(this, settings, FakeBackend(row))
        runCurrent()
        inbox.save(null, " Work ", "https://work.example.com/prefix/", "work-token") {}
        runCurrent()
        assertEquals(listOf("Home", "Work"), inbox.state.value.connections.map { it.name })
        assertEquals(connection.id, inbox.state.value.connections.first().id)
        inbox.save(null, "work", "https://different.example.com", "token") {}
        runCurrent()
        assertEquals(Problem.DUPLICATE_NAME, inbox.state.value.settingsProblem)
        inbox.save(null, "Alias", "https://WORK.example.com:443/prefix", "token") {}
        runCurrent()
        assertEquals(Problem.DUPLICATE_URL, inbox.state.value.settingsProblem)
        assertEquals(2, inbox.state.value.connections.size)
    }

    private class MemorySettings(var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }

    private class FakeBackend(val row: AlertRow) : Backend {
        var historyCalls = 0
        var problem: Problem? = null
        override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
            historyCalls++
            problem?.let { throw InboxFailure(it) }
            return HistoryPage(listOf(row))
        }
        override suspend fun read(connection: Connection, id: String) = AlertDetail(row, "Original summary")
    }
}
