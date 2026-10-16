package no.varde.app.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CombinedInboxTest {
    @Test fun replenishesEachSourceBeforeEmittingAnOlderGlobalCandidate() = runTest {
        val home = Connection.create("Home", "https://home.example.com", "token", "a")
        val work = Connection.create("Work", "https://work.example.com", "token", "b")
        val calls = mutableListOf<Pair<String, String?>>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                calls += connection.id to cursor
                return when (connection.id to cursor) {
                    "a" to null -> HistoryPage(listOf(row("six", "2026-01-01T00:00:06Z"), row("four", "2026-01-01T00:00:04Z")), "home-next")
                    "a" to "home-next" -> HistoryPage(listOf(row("three", "2026-01-01T00:00:03Z"), row("two", "2026-01-01T00:00:02Z")))
                    "b" to null -> HistoryPage(listOf(row("five", "2026-01-01T00:00:05Z")), "work-next")
                    "b" to "work-next" -> HistoryPage(listOf(row("one", "2026-01-01T00:00:01Z")))
                    else -> error("Unexpected continuation")
                }
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(listOf(home, work)), backend)
        runCurrent()
        assertEquals(listOf("six", "five", "four", "three", "two", "one"), inbox.state.value.items.map { it.row.id })
        assertEquals(setOf("a" to null, "a" to "home-next", "b" to null, "b" to "work-next"), calls.toSet())
    }

    @Test fun comparesInstantsAndKeepsSourceScopedIdentitiesAndDetails() = runTest {
        val home = Connection.create("Zulu", "https://home.example.com", "token", "a")
        val work = Connection.create("Alpha", "https://work.example.com", "token", "b")
        val reads = mutableListOf<AlertKey>()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(
                if (connection.id == "a") listOf(row("z", "2026-01-01T12:00:00.1Z"), row("a", "2026-01-01T12:00:00.100000000Z"))
                else listOf(row("z", "2026-01-01T14:00:00.100+02:00"), row("old", "2026-01-01T12:00:00Z"))
            )
            override suspend fun read(connection: Connection, id: String): AlertDetail {
                reads += AlertKey(connection.id, id)
                return AlertDetail(row(id, "2026-01-01T12:00:00Z"), connection.name)
            }
        }
        val inbox = Inbox(this, TestSettings(listOf(work, home)), backend)
        runCurrent()
        assertEquals(listOf(AlertKey("a", "z"), AlertKey("a", "a"), AlertKey("b", "z"), AlertKey("b", "old")), inbox.state.value.items.map { it.key })
        inbox.open(AlertKey("b", "z"))
        runCurrent()
        assertEquals(listOf(AlertKey("b", "z")), reads)
        assertEquals("Alpha", inbox.state.value.detail?.alert?.summary)
    }

    @Test fun capsTheFirstBatchWithoutDrainingSources() = runTest {
        val connections = listOf(Connection.create("Home", "https://home.example.com", "token"), Connection.create("Work", "https://work.example.com", "token"))
        var requests = 0
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                assertNull(cursor)
                requests++
                return HistoryPage((59 downTo 10).map { row("item-$it", "2026-01-01T00:00:${it.toString().padStart(2, '0')}Z") }, "do-not-prefetch")
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(connections), backend)
        runCurrent()
        assertEquals(50, inbox.state.value.items.size)
        assertEquals(2, requests)
        assertEquals("item-59", inbox.state.value.items.first().row.id)
        assertEquals("item-35", inbox.state.value.items.last().row.id)
    }

    @Test fun limitsConcurrentRequestsToFourAndTimesOutWithoutRetry() = runTest {
        val connections = (1..5).map { Connection.create("Lab $it", "https://lab$it.example.com", "token", "$it") }
        val gates = connections.associate { it.id to CompletableDeferred<Unit>() }
        var active = 0
        var peak = 0
        var calls = 0
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                calls++; active++; peak = maxOf(peak, active)
                try { gates.getValue(connection.id).await() } finally { active-- }
                return HistoryPage(emptyList())
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(connections), backend)
        runCurrent()
        assertEquals(4, calls)
        assertEquals(4, peak)
        gates.getValue("1").complete(Unit)
        runCurrent()
        assertEquals(5, calls)
        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(4, peak)
        assertFalse(inbox.state.value.loading)

        var failedCalls = 0
        val timeoutBackend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                failedCalls++
                if (connection.id == "1") awaitCancellation()
                return HistoryPage(listOf(row("visible", "2026-01-01T00:00:01Z")))
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val partial = Inbox(this, TestSettings(connections.take(2)), timeoutBackend)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf("visible"), partial.state.value.items.map { it.row.id })
        assertEquals(Problem.NETWORK, partial.state.value.problems["1"])
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, failedCalls)
    }

    @Test fun malformedSourceCannotLoopOrHideHealthyResults() = runTest {
        val invalid = Connection.create("Invalid", "https://invalid.example.com", "token", "bad")
        val healthy = Connection.create("Healthy", "https://healthy.example.com", "token", "good")
        var requests = 0
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                requests++
                return if (connection.id == "bad") HistoryPage(emptyList(), "never-advance")
                    else HistoryPage(listOf(row("visible", "2026-01-01T00:00:01Z")))
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(this, TestSettings(listOf(invalid, healthy)), backend)
        runCurrent()
        assertEquals(2, requests)
        assertEquals(Problem.RESPONSE, inbox.state.value.problems["bad"])
        assertEquals(listOf("visible"), inbox.state.value.items.map { it.row.id })
    }

    private fun row(id: String, accepted: String) = AlertRow(id, accepted, id, "normal", "2020-01-01T00:00:00Z", Presentation("fallback", 1))

    private class TestSettings(var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }
}
