package no.varde.app.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PartialOutageTest {
    private val home = Connection.create("Home", "https://home.example.com", "home-token", "home")
    private val work = Connection.create("Work", "https://work.example.com", "work-token", "work")
    private val homeRow = row("home-alert", 200)
    private val workRow = row("work-alert", 100)

    @Test fun failedRefreshRetainsRowsAndLoadedDetailAndMarksTheirSourceStale() = runTest {
        var offline = false
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                if (connection.id == home.id && offline) throw InboxFailure(Problem.NETWORK)
                return HistoryPage(listOf(if (connection.id == home.id) homeRow else workRow))
            }
            override suspend fun read(connection: Connection, id: String) = AlertDetail(homeRow, "Retained original")
        }
        val inbox = Inbox(this, Settings(listOf(home, work)), backend)
        runCurrent()
        inbox.open(AlertKey(home.id, homeRow.id))
        runCurrent()
        offline = true
        inbox.refresh()
        runCurrent()
        assertEquals(listOf(homeRow, workRow), inbox.state.value.items.map { it.row })
        assertEquals("Retained original", inbox.state.value.detail?.alert?.summary)
        assertTrue(inbox.state.value.detail!!.stale)
        assertEquals(setOf(home.id), inbox.state.value.staleConnections)
        assertFalse(inbox.state.value.historyComplete)
        offline = false
        inbox.refresh()
        runCurrent()
        assertFalse(inbox.state.value.detail!!.stale)
        assertTrue(inbox.state.value.problems.isEmpty())
        assertTrue(inbox.state.value.historyComplete)
    }

    @Test fun reopeningAPausedSourceUsesOnlyPreviouslyLoadedDetails() = runTest {
        var offline = false
        var reads = 0
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                if (offline) throw InboxFailure(Problem.NETWORK)
                return HistoryPage(listOf(homeRow, row("unopened", 150)))
            }
            override suspend fun read(connection: Connection, id: String): AlertDetail {
                reads++
                if (offline) throw InboxFailure(Problem.NETWORK)
                return AlertDetail(homeRow, "Cached original")
            }
        }
        val inbox = Inbox(this, Settings(listOf(home)), backend)
        runCurrent()
        inbox.open(AlertKey(home.id, homeRow.id))
        runCurrent()
        inbox.closeDetail()
        offline = true
        inbox.refresh()
        runCurrent()
        inbox.open(AlertKey(home.id, homeRow.id))
        runCurrent()
        assertEquals("Cached original", inbox.state.value.detail?.alert?.summary)
        assertTrue(inbox.state.value.detail!!.stale)
        assertEquals(1, reads)
        inbox.open(AlertKey(home.id, "unopened"))
        runCurrent()
        assertNull(inbox.state.value.detail?.alert)
        assertFalse(inbox.state.value.detail!!.loading)
        assertEquals(Problem.NETWORK, inbox.state.value.detail?.sourceProblem)
        assertEquals(1, reads)
    }

    @Test fun revokedDetailAccessMarksRowsStaleAndPausesOnlyThatHistorySource() = runTest {
        var revoked = false
        val calls = mutableListOf<Pair<String, String?>>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                calls += connection.id to cursor
                if (connection.id == home.id && revoked) throw InboxFailure(Problem.AUTHENTICATION)
                return if (connection.id == home.id) HistoryPage((200 downTo 151).map { row("home-$it", it.toLong()) }, "home-older")
                    else HistoryPage((100 downTo 51).map { row("work-$it", it.toLong()) })
            }
            override suspend fun read(connection: Connection, id: String): AlertDetail {
                if (revoked) throw InboxFailure(Problem.AUTHENTICATION)
                return AlertDetail(row(id, 200), "Retained authorized original")
            }
        }
        val inbox = Inbox(this, Settings(listOf(home, work)), backend)
        runCurrent()
        val key = inbox.state.value.items.first().key
        inbox.open(key)
        runCurrent()
        inbox.closeDetail()
        revoked = true
        inbox.open(key)
        runCurrent()
        assertEquals("Retained authorized original", inbox.state.value.detail?.alert?.summary)
        assertEquals(Problem.AUTHENTICATION, inbox.state.value.problems[home.id])
        assertTrue(home.id in inbox.state.value.staleConnections)
        assertEquals(listOf(home, work), inbox.state.value.connections)
        inbox.loadOlder()
        runCurrent()
        assertEquals(100, inbox.state.value.items.size)
        assertEquals(listOf(home.id to null, work.id to null), calls)
        assertFalse(inbox.state.value.historyComplete)
    }

    @Test fun retryingOneSourceStartsFreshSnapshotsForEveryConnection() = runTest {
        var recovered = false
        val calls = mutableListOf<Pair<String, String?>>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                calls += connection.id to cursor
                if (connection.id == work.id) return HistoryPage((100 downTo 51).map { row("work-$it", it.toLong()) })
                return when (cursor) {
                    "home-v1" -> throw InboxFailure(Problem.HISTORY_CURSOR)
                    "home-v2" -> HistoryPage((200 downTo 151).map { row("home-$it", it.toLong()) })
                    else -> if (recovered) HistoryPage((500 downTo 451).map { row("new-$it", it.toLong()) }, "home-v2")
                        else HistoryPage((200 downTo 151).map { row("home-$it", it.toLong()) }, "home-v1")
                }
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, Settings(listOf(home, work)), backend)
        runCurrent()
        inbox.loadOlder()
        runCurrent()
        assertEquals(100, inbox.state.value.items.size)
        assertEquals(Problem.HISTORY_CURSOR, inbox.state.value.problems[home.id])
        recovered = true
        inbox.retryConnection(home.id)
        runCurrent()
        assertEquals((500 downTo 451).map { "new-$it" }, inbox.state.value.items.map { it.row.id })
        assertTrue(inbox.state.value.problems.isEmpty())
        assertEquals(2, calls.count { it == work.id to null })
        inbox.loadOlder()
        runCurrent()
        assertEquals(listOf(null, "home-v1", null, "home-v2"), calls.filter { it.first == home.id }.map { it.second })
        assertTrue(inbox.state.value.items.none { it.key.connectionId == work.id })
        inbox.loadOlder()
        runCurrent()
        assertEquals(150, inbox.state.value.items.size)
        assertTrue(inbox.state.value.historyComplete)
    }

    @Test fun stagedRefreshRetainsOnlyDisplayedStaleRowsAcrossRepeatedFailures() = runTest {
        val newWorkPage = CompletableDeferred<HistoryPage>()
        var failing = false
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                if (!failing) return if (connection.id == home.id) HistoryPage((200 downTo 151).map { row("home-$it", it.toLong()) })
                    else HistoryPage((100 downTo 51).map { row("unseen-$it", it.toLong()) })
                if (connection.id == home.id) throw InboxFailure(Problem.NETWORK)
                return newWorkPage.await()
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, Settings(listOf(home, work)), backend)
        runCurrent()
        val previous = inbox.state.value.items
        failing = true
        inbox.refresh()
        runCurrent()
        assertTrue(inbox.state.value.loading)
        assertEquals(previous, inbox.state.value.items)
        newWorkPage.complete(HistoryPage((400 downTo 351).map { row("fresh-$it", it.toLong()) }))
        runCurrent()
        assertEquals((400 downTo 351).map { "fresh-$it" } + (200 downTo 151).map { "home-$it" }, inbox.state.value.items.map { it.row.id })
        assertEquals(100, inbox.state.value.items.size)
        assertFalse(inbox.state.value.historyComplete)
        assertTrue(inbox.state.value.items.none { it.row.id.startsWith("unseen-") })
        repeat(2) { inbox.refresh(); runCurrent() }
        assertEquals(100, inbox.state.value.items.size)
        assertEquals(100, inbox.state.value.items.map { it.key }.distinct().size)
    }

    @Test fun aLateHistoryResponseCannotUndoAConnectionFailureFromDetails() = runTest {
        var revoked = false
        val late = CompletableDeferred<HistoryPage>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                if (cursor != null) return withContext(NonCancellable) { late.await() }
                return if (connection.id == home.id) HistoryPage((200 downTo 151).map { row("home-$it", it.toLong()) }, "late-home")
                    else HistoryPage((100 downTo 51).map { row("work-$it", it.toLong()) })
            }
            override suspend fun read(connection: Connection, id: String): AlertDetail {
                if (revoked) throw InboxFailure(Problem.AUTHENTICATION)
                return AlertDetail(row(id, 200), "Retained original")
            }
        }
        val inbox = Inbox(this, Settings(listOf(home, work)), backend)
        runCurrent()
        val key = inbox.state.value.items.first().key
        inbox.open(key)
        runCurrent()
        inbox.loadOlder()
        runCurrent()
        revoked = true
        inbox.retryDetail()
        runCurrent()
        late.complete(HistoryPage((150 downTo 101).map { row("obsolete-$it", it.toLong()) }))
        runCurrent()
        assertEquals(Problem.AUTHENTICATION, inbox.state.value.problems[home.id])
        assertTrue(inbox.state.value.detail!!.stale)
        assertEquals("Retained original", inbox.state.value.detail?.alert?.summary)
        assertEquals(100, inbox.state.value.items.size)
        assertTrue(inbox.state.value.items.none { it.row.id.startsWith("obsolete-") })
        assertFalse(inbox.state.value.historyComplete)
    }

    @Test fun detailNotFoundIsLocalAndNeverInventsContentOrRemovesRows() = runTest {
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(listOf(if (connection.id == home.id) homeRow else workRow))
            override suspend fun read(connection: Connection, id: String): AlertDetail = throw InboxFailure(Problem.NOT_FOUND)
        }
        val inbox = Inbox(this, Settings(listOf(home, work)), backend)
        runCurrent()
        val previous = inbox.state.value.items
        inbox.open(AlertKey(home.id, homeRow.id))
        runCurrent()
        assertEquals(Problem.NOT_FOUND, inbox.state.value.detail?.problem)
        assertNull(inbox.state.value.detail?.alert)
        assertFalse(inbox.state.value.detail!!.stale)
        assertEquals(previous, inbox.state.value.items)
        assertTrue(inbox.state.value.problems.isEmpty())
        assertTrue(inbox.state.value.historyComplete)
    }

    @Test fun replacingAccessClearsCachedBodiesAndRestartDoesNotRetainThem() = runTest {
        var fail = false
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                if (fail) throw InboxFailure(Problem.NETWORK)
                return HistoryPage(listOf(homeRow))
            }
            override suspend fun read(connection: Connection, id: String): AlertDetail {
                if (connection.secret == "replacement") throw InboxFailure(Problem.NETWORK)
                return AlertDetail(homeRow, "Old authorized body")
            }
        }
        val settings = Settings(listOf(home))
        val inbox = Inbox(this, settings, backend)
        runCurrent()
        val key = AlertKey(home.id, homeRow.id)
        inbox.open(key)
        runCurrent()
        inbox.closeDetail()
        inbox.save(home.id, home.name, home.baseUrl.toString(), "replacement") {}
        runCurrent()
        inbox.open(key)
        runCurrent()
        assertNull(inbox.state.value.detail?.alert)
        assertEquals(Problem.NETWORK, inbox.state.value.detail?.problem)
        fail = true
        val restarted = Inbox(this, settings, backend)
        runCurrent()
        assertTrue(restarted.state.value.items.isEmpty())
        assertNull(restarted.state.value.detail)
    }

    @Test fun healthyHistoryContinuesAroundSlowRevokedAndUnreachableSources() = runTest {
        val slow = Connection.create("Slow", "https://slow.example.com", "token", "slow")
        val calls = mutableListOf<Pair<String, String?>>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                calls += connection.id to cursor
                when (connection.id) {
                    home.id -> throw InboxFailure(Problem.AUTHENTICATION)
                    slow.id -> awaitCancellation()
                }
                return if (cursor == null) HistoryPage((200 downTo 151).map { row("work-$it", it.toLong()) }, "work-next")
                    else HistoryPage((150 downTo 101).map { row("work-$it", it.toLong()) })
            }
            override suspend fun read(connection: Connection, id: String) = error("No details requested")
        }
        val inbox = Inbox(this, Settings(listOf(home, slow, work)), backend)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(50, inbox.state.value.items.size)
        assertEquals(mapOf(home.id to Problem.AUTHENTICATION, slow.id to Problem.NETWORK), inbox.state.value.problems)
        inbox.loadOlder()
        runCurrent()
        assertEquals(100, inbox.state.value.items.size)
        assertFalse(inbox.state.value.historyComplete)
        assertFalse(inbox.state.value.hasMore)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, calls.size)
        assertEquals(1, calls.count { it.first == home.id })
        assertEquals(1, calls.count { it.first == slow.id })
    }

    private fun row(id: String, seconds: Long) = AlertRow(id, java.time.Instant.ofEpochSecond(seconds).toString(), id, "normal", "2026-01-01T00:00:00Z", Presentation("fallback", 1))
    private class Settings(private var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }
}
