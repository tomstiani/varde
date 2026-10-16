package no.varde.app.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaginationTest {
    @Test fun consumesBufferedCandidatesBeforeFetchingAndStopsAtSnapshotEnd() = runTest {
        val home = Connection.create("Home", "https://home.example.com", "token", "a")
        val work = Connection.create("Work", "https://work.example.com", "token", "b")
        val calls = mutableListOf<Pair<String, String?>>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                calls += connection.id to cursor
                return when (connection.id to cursor) {
                    "a" to null -> HistoryPage((200 downTo 151).map(::row), "home-older")
                    "a" to "home-older" -> HistoryPage((150 downTo 101).map(::row))
                    "b" to null -> HistoryPage((100 downTo 51).map(::row))
                    else -> error("Unexpected continuation")
                }
            }
            override suspend fun read(connection: Connection, id: String) = error("No details requested")
        }
        val inbox = Inbox(this, TestSettings(listOf(home, work)), backend)
        runCurrent()
        assertEquals((200 downTo 151).map { "alert-$it" }, inbox.state.value.items.map { it.row.id })
        assertEquals(2, calls.size)
        assertTrue(inbox.state.value.hasMore)
        inbox.loadOlder()
        runCurrent()
        assertEquals((200 downTo 101).map { "alert-$it" }, inbox.state.value.items.map { it.row.id })
        assertEquals(listOf("a" to null, "b" to null, "a" to "home-older"), calls)
        inbox.loadOlder()
        runCurrent()
        assertEquals((200 downTo 51).map { "alert-$it" }, inbox.state.value.items.map { it.row.id })
        assertEquals(3, calls.size)
        assertFalse(inbox.state.value.hasMore)
        assertTrue(inbox.state.value.historyComplete)
        inbox.loadOlder()
        runCurrent()
        assertEquals(3, calls.size)
    }

    @Test fun coalescesRefreshAndLoadOlderGestures() = runTest {
        val connection = Connection.create("Home", "https://example.com", "token")
        val first = CompletableDeferred<HistoryPage>()
        val next = CompletableDeferred<HistoryPage>()
        var calls = 0
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                calls++
                return if (cursor == null) first.await() else next.await()
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(listOf(connection)), backend)
        runCurrent()
        repeat(10) { inbox.refresh() }
        runCurrent()
        assertEquals(1, calls)
        first.complete(HistoryPage((100 downTo 51).map(::row), "older"))
        runCurrent()
        repeat(10) { inbox.loadOlder() }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(inbox.state.value.loadingOlder)
        assertEquals(50, inbox.state.value.items.size)
        next.complete(HistoryPage((50 downTo 1).map(::row)))
        runCurrent()
        assertEquals(100, inbox.state.value.items.size)
        assertTrue(inbox.state.value.historyComplete)
    }

    @Test fun refreshCancelsOlderResultsWithoutChangingTheFreshSession() = runTest {
        val connection = Connection.create("Home", "https://example.com", "token")
        val obsolete = CompletableDeferred<HistoryPage>()
        var fresh = false
        val cursors = mutableListOf<String?>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                cursors += cursor
                return when {
                    cursor == "old-older" -> withContext(NonCancellable) { obsolete.await() }
                    cursor == "new-older" -> HistoryPage((150 downTo 141).map(::row))
                    fresh -> HistoryPage((200 downTo 151).map(::row), "new-older")
                    else -> HistoryPage((100 downTo 51).map(::row), "old-older")
                }
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(listOf(connection)), backend)
        runCurrent()
        inbox.loadOlder()
        runCurrent()
        fresh = true
        inbox.refresh()
        runCurrent()
        obsolete.complete(HistoryPage((50 downTo 1).map(::row)))
        runCurrent()
        assertEquals((200 downTo 151).map { "alert-$it" }, inbox.state.value.items.map { it.row.id })
        inbox.loadOlder()
        runCurrent()
        assertEquals((200 downTo 141).map { "alert-$it" }, inbox.state.value.items.map { it.row.id })
        assertEquals(listOf(null, "old-older", null, "new-older"), cursors)
    }

    @Test fun failedPageIsNotExhaustionAndDoesNotRetryOrConsumeAnotherCursor() = runTest {
        val home = Connection.create("Home", "https://home.example.com", "token", "a")
        val work = Connection.create("Work", "https://work.example.com", "token", "b")
        val requests = mutableListOf<Pair<String, String?>>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                requests += connection.id to cursor
                if (cursor != null) throw InboxFailure(Problem.NETWORK)
                return if (connection.id == "a") HistoryPage((200 downTo 151).map(::row), "failed-cursor")
                    else HistoryPage((50 downTo 31).map(::row))
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(listOf(home, work)), backend)
        runCurrent()
        inbox.loadOlder()
        runCurrent()
        assertEquals(70, inbox.state.value.items.size)
        assertFalse(inbox.state.value.hasMore)
        assertFalse(inbox.state.value.historyComplete)
        assertEquals(Problem.NETWORK, inbox.state.value.problems["a"])
        repeat(3) { inbox.loadOlder() }
        runCurrent()
        assertEquals(listOf("a" to null, "b" to null, "a" to "failed-cursor"), requests)
    }

    @Test fun equalInstantsAndDuplicateServerIdsStayStableAcrossBatches() = runTest {
        val home = Connection.create("Home", "https://home.example.com", "token", "a")
        val work = Connection.create("Work", "https://work.example.com", "token", "b")
        val empty = Connection.create("Empty", "https://empty.example.com", "token", "c")
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                if (connection.id == "c") return HistoryPage(emptyList())
                val offset = cursor?.toInt() ?: 0
                val rows = (149 downTo 75).map {
                    row(it).copy(id = "id-${it.toString().padStart(3, '0')}", acceptedAt = if (connection.id == "a") "2026-01-01T12:00:00.1Z" else "2026-01-01T14:00:00.100000000+02:00")
                }
                return HistoryPage(rows.drop(offset).take(50), if (offset == 0) "50" else null)
            }
            override suspend fun read(connection: Connection, id: String) = AlertDetail(row(149).copy(id = id), connection.name)
        }
        val inbox = Inbox(this, TestSettings(listOf(work, empty, home)), backend)
        runCurrent()
        val opened = inbox.state.value.items.first().key
        inbox.open(opened)
        runCurrent()
        repeat(2) { inbox.loadOlder(); runCurrent() }
        val expected = listOf("a", "b").flatMap { source -> (149 downTo 75).map { AlertKey(source, "id-${it.toString().padStart(3, '0')}") } }
        assertEquals(expected, inbox.state.value.items.map { it.key })
        assertEquals(opened, inbox.state.value.detail?.key)
        assertEquals("Home", inbox.state.value.detail?.alert?.summary)
        assertTrue(inbox.state.value.historyComplete)
    }

    @Test fun accessChangeInvalidatesOlderRequestsAndTheirCursorState() = runTest {
        val connection = Connection.create("Home", "https://example.com", "token")
        val obsolete = CompletableDeferred<HistoryPage>()
        val requests = mutableListOf<String?>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                requests += cursor
                return when {
                    cursor == "old" -> withContext(NonCancellable) { obsolete.await() }
                    cursor == "new" -> HistoryPage((150 downTo 141).map(::row))
                    connection.secret == "replacement" -> HistoryPage((200 downTo 151).map(::row), "new")
                    else -> HistoryPage((100 downTo 51).map(::row), "old")
                }
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(listOf(connection)), backend)
        runCurrent()
        inbox.loadOlder()
        runCurrent()
        inbox.save(connection.id, connection.name, connection.baseUrl.toString(), "replacement") {}
        runCurrent()
        obsolete.complete(HistoryPage((50 downTo 1).map(::row)))
        runCurrent()
        assertEquals((200 downTo 151).map { "alert-$it" }, inbox.state.value.items.map { it.row.id })
        assertFalse(inbox.state.value.loadingOlder)
        inbox.loadOlder()
        runCurrent()
        assertEquals((200 downTo 141).map { "alert-$it" }, inbox.state.value.items.map { it.row.id })
        assertEquals(listOf(null, "old", null, "new"), requests)
    }

    private fun row(number: Int) = AlertRow("alert-$number", java.time.Instant.ofEpochSecond(number.toLong()).toString(), "Alert $number", "normal", "2026-01-01T00:00:00Z", Presentation("fallback", 1))

    private class TestSettings(private val connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) = Unit
    }
}
