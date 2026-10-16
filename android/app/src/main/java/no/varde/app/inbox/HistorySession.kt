package no.varde.app.inbox

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.DateTimeException
import java.time.Instant

internal data class HistoryBatch(
    val items: List<InboxItem>,
    val problems: Map<String, Problem>,
    val session: HistorySession,
)

internal val inboxOrdering: Comparator<InboxItem> = compareByDescending<InboxItem> { Instant.parse(it.row.acceptedAt) }
    .thenBy { it.key.connectionId }.thenByDescending { it.key.alertId }

internal class HistorySession private constructor(private val sources: List<SourceBuffer>) {
    val hasMore: Boolean get() = sources.any { it.problem == null && it.hasMore }
    val complete: Boolean get() = sources.all { it.problem == null && !it.hasMore }

    fun withFailures(failures: Map<String, Problem>, previous: HistorySession? = this): HistorySession {
        if (failures.isEmpty()) return this
        // Roll back only failed sources to committed positions; keep healthy continuation progress.
        return HistorySession(sources.map { source ->
            val problem = failures[source.connection.id]
            if (problem == null) source else {
                val committed = previous?.sources?.find { it.connection.id == source.connection.id }
                (committed ?: SourceBuffer(source.connection)).fork().apply { pause(problem) }
            }
        })
    }

    suspend fun nextBatch(backend: Backend): HistoryBatch = coroutineScope {
        // Only the caller commits the returned session; cancellation cannot consume its buffers or cursors.
        val working = sources.map { it.fork() }
        working.map { async { it.ensureCandidate(backend) } }.awaitAll()
        val selected = mutableListOf<InboxItem>()
        while (selected.size < 50) {
            val source = working.filter { it.items.isNotEmpty() && it.problem == null }
                .minWithOrNull { left, right -> inboxOrdering.compare(left.items.first(), right.items.first()) } ?: break
            selected += source.items.removeFirst()
            // Refill before comparing against other heads, not after appending an older candidate.
            if (selected.size < 50) source.ensureCandidate(backend)
        }
        HistoryBatch(selected, working.mapNotNull { source -> source.problem?.let { source.connection.id to it } }.toMap(), HistorySession(working))
    }

    companion object {
        fun begin(connections: List<Connection>) = HistorySession(connections.map(::SourceBuffer))
    }
}

private class SourceBuffer(val connection: Connection) {
    val items = ArrayDeque<InboxItem>()
    var problem: Problem? = null
        private set
    private var started = false
    private var nextCursor: String? = null
    private var lastReceived: InboxItem? = null
    val hasMore: Boolean get() = items.isNotEmpty() || !started || nextCursor != null

    fun pause(failure: Problem) { problem = failure }

    fun fork() = SourceBuffer(connection).also {
        it.items.addAll(items)
        it.problem = problem
        it.started = started
        it.nextCursor = nextCursor
        it.lastReceived = lastReceived
    }

    suspend fun ensureCandidate(backend: Backend) {
        if (items.isNotEmpty() || problem != null || (started && nextCursor == null)) return
        try {
            val page = backend.history(connection, nextCursor)
            val incoming = validatePage(page)
            items.addAll(incoming)
            lastReceived = incoming.lastOrNull() ?: lastReceived
            nextCursor = page.nextCursor
            started = true
        } catch (failure: InboxFailure) {
            problem = failure.problem
        } catch (_: IllegalArgumentException) {
            problem = Problem.RESPONSE
        } catch (_: DateTimeException) {
            problem = Problem.RESPONSE
        }
    }

    private fun validatePage(page: HistoryPage): List<InboxItem> {
        require(page.items.size <= 50)
        page.nextCursor?.let { require(it.isNotBlank() && it.length <= 2048 && it != nextCursor && page.items.isNotEmpty()) }
        val incoming = page.items.map { row ->
            Instant.parse(row.acceptedAt)
            InboxItem(AlertKey(connection.id, row.id), row)
        }
        var previous = lastReceived
        for (item in incoming) {
            previous?.let { require(inboxOrdering.compare(it, item) < 0) }
            previous = item
        }
        return incoming
    }
}
