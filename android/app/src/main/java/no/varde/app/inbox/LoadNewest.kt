package no.varde.app.inbox

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.DateTimeException
import java.time.Instant

internal data class NewestResult(val items: List<InboxItem>, val problems: Map<String, Problem>)

internal val inboxOrdering: Comparator<InboxItem> = compareByDescending<InboxItem> { Instant.parse(it.row.acceptedAt) }
    .thenBy { it.key.connectionId }.thenByDescending { it.key.alertId }

internal suspend fun loadNewest(connections: List<Connection>, backend: Backend): NewestResult = coroutineScope {
    val sources = connections.map { SourceBuffer(it, backend) }
    sources.map { async { it.ensureCandidate() } }.awaitAll()
    val selected = mutableListOf<InboxItem>()
    while (selected.size < 50) {
        val source = sources.filter { it.items.isNotEmpty() }
            .minWithOrNull { left, right -> inboxOrdering.compare(left.items.first(), right.items.first()) } ?: break
        selected += source.items.removeFirst()
        // Refill before comparing against other heads; concatenated page sorting loses rows.
        if (selected.size < 50) source.ensureCandidate()
    }
    NewestResult(selected, sources.mapNotNull { source -> source.problem?.let { source.connection.id to it } }.toMap())
}

private class SourceBuffer(val connection: Connection, private val backend: Backend) {
    val items = ArrayDeque<InboxItem>()
    var problem: Problem? = null
        private set
    private var started = false
    private var nextCursor: String? = null
    private var lastReceived: InboxItem? = null

    suspend fun ensureCandidate() {
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
