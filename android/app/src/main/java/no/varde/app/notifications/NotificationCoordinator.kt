package no.varde.app.notifications

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.varde.app.inbox.*

class AlertSignal(val version: String, val registrationId: String, val alertId: String)
data class AlertNotice(val targetId: String, val source: String, val detail: AlertDetail? = null)
data class CatchUpNotice(val targetId: String, val source: String, val count: Int)
class NotificationDestination(val key: AlertKey?, val connection: Connection)
private class RecoveryRequestFailure(val registrationId: String, val cursor: String?, val problem: Problem) : Exception()

interface NotificationOutput {
    fun enabled(): Boolean
    fun show(notice: AlertNotice, interrupt: Boolean)
    fun catchUp(notice: CatchUpNotice, interrupt: Boolean) { error("Catch-up output is not configured") }
    fun active(targetId: String): Boolean
    fun cancel(targetId: String)
    fun rename(targetId: String, source: String)
}

class NotificationCoordinator(
    private val settings: ConnectionSettings,
    backend: Backend,
    directory: File,
    private val output: NotificationOutput,
) {
    private val backend = BoundedBackend(backend) { null }
    private val journal = NotificationJournal(directory)
    private val pendingTargets = mutableSetOf<String>()
    private val recovering = mutableSetOf<String>()
    private val mutableRecoveryProblems = MutableStateFlow<Map<String, Problem>>(emptyMap())
    val recoveryProblems = mutableRecoveryProblems.asStateFlow()
    private val mutex = locks.getOrPut(directory.canonicalPath) { Mutex() }

    suspend fun activate(connectionId: String, registrationId: String) = locked {
        val connection = settings.load().firstOrNull { it.id == connectionId } ?: return@locked
        require(registrationId.matches(Regex("[0-9a-f]{32}")))
        val state = journal.load()
        reconcile(state, settings.load())
        val existing = state.sessions.firstOrNull { it.registrationId == registrationId }
        if (existing != null) {
            if (existing.retired || existing.connectionId != connectionId || existing.access != access(connection)) {
                throw InboxFailure(Problem.CONFIGURATION)
            }
            return@locked
        }
        val retired = state.sessions.filter { it.connectionId == connectionId && !it.retired }
        retired.forEach { it.retired = true }
        state.sessions += NotificationSession(connectionId, registrationId, access(connection))
        journal.save(state)
        state.entries.filter { entry -> retired.any { it.registrationId == entry.registrationId } }.forEach { output.cancel(it.targetId) }
        retired.forEach { it.summaryTarget?.let(output::cancel) }
    }

    suspend fun currentRegistration(connectionId: String): String? = locked {
        val state = journal.load()
        reconcile(state, settings.load())
        state.sessions.firstOrNull { it.connectionId == connectionId && !it.retired }?.registrationId
    }

    suspend fun receive(signal: AlertSignal) {
        if (signal.version != "1" || !signal.registrationId.matches(Regex("[0-9a-f]{32}")) ||
            !signal.alertId.matches(Regex("alert_[A-Za-z0-9_-]{32}"))) return
        val pending = locked {
            val state = journal.load()
            val connections = settings.load()
            reconcile(state, connections)
            val session = state.sessions.firstOrNull { it.registrationId == signal.registrationId && !it.retired } ?: return@locked null
            val connection = connections.firstOrNull { it.id == session.connectionId } ?: return@locked null
            var entry = state.entries.firstOrNull { it.registrationId == signal.registrationId && it.alertId == signal.alertId }
            val fresh = entry == null
            if (entry == null) {
                entry = NotificationEntry(signal.registrationId, signal.alertId, UUID.randomUUID().toString().replace("-", ""))
                entry.dismissed = !output.enabled()
                state.entries += entry
                journal.save(state)
                if (!entry.dismissed) output.show(AlertNotice(entry.targetId, connection.name), true)
            }
            if (entry.dismissed || entry.targetId in pendingTargets) return@locked null
            if (!output.enabled() || (!fresh && !output.active(entry.targetId))) {
                entry.dismissed = true
                journal.save(state)
                output.cancel(entry.targetId)
                return@locked null
            }
            pendingTargets += entry.targetId
            connection to entry.targetId
        } ?: return
        try {
            val detail = try { backend.read(pending.first, signal.alertId) } catch (_: InboxFailure) { return }
            // NotificationManager publication can lag a fast detail read.
            withTimeoutOrNull(1_000) { while (!output.active(pending.second)) delay(10) }
            locked {
                val state = journal.load()
                val connections = settings.load()
                reconcile(state, connections)
                val session = state.sessions.firstOrNull { it.registrationId == signal.registrationId && !it.retired } ?: return@locked
                val entry = state.entries.firstOrNull { it.targetId == pending.second } ?: return@locked
                val connection = connections.first { it.id == session.connectionId }
                if (!entry.dismissed && output.enabled() && output.active(entry.targetId)) {
                    output.show(AlertNotice(entry.targetId, connection.name, detail), false)
                }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { pendingTargets -= pending.second } }
        }
    }

    suspend fun recoverAll() = coroutineScope {
        val connections = settings.load()
        mutableRecoveryProblems.update { problems -> problems.filterKeys { id -> connections.any { it.id == id } } }
        connections.forEach { connection -> launch { recover(connection.id) } }
    }

    suspend fun recover(connectionId: String, maxPages: Int = 4) {
        require(maxPages in 1..20)
        if (!mutex.withLock { recovering.add(connectionId) }) return
        try {
            repeat(maxPages) {
                val more = recoverPage(connectionId)
                mutableRecoveryProblems.update { it - connectionId }
                if (!more) return
            }
            mutableRecoveryProblems.update { it + (connectionId to Problem.RECOVERY_PENDING) }
        } catch (failure: RecoveryRequestFailure) {
            try {
                locked {
                    val state = journal.load()
                    reconcile(state, settings.load())
                    val session = state.sessions.firstOrNull { it.registrationId == failure.registrationId && !it.retired && it.cursor == failure.cursor } ?: return@locked
                    if (failure.problem == Problem.RECOVERY_CURSOR) {
                        session.retired = true
                        journal.save(state)
                        state.entries.filter { it.registrationId == session.registrationId }.forEach { output.cancel(it.targetId) }
                        session.summaryTarget?.let(output::cancel)
                    }
                    mutableRecoveryProblems.update { it + (connectionId to failure.problem) }
                }
            } catch (storage: InboxFailure) { mutableRecoveryProblems.update { it + (connectionId to storage.problem) } }
        } catch (failure: InboxFailure) {
            mutableRecoveryProblems.update { it + (connectionId to failure.problem) }
        } finally {
            withContext(NonCancellable) { mutex.withLock { recovering -= connectionId } }
        }
    }

    private suspend fun recoverPage(connectionId: String): Boolean {
        val request = locked {
            val state = journal.load()
            val connections = settings.load()
            reconcile(state, connections)
            val connection = connections.firstOrNull { it.id == connectionId } ?: return@locked null
            val session = state.sessions.firstOrNull { it.connectionId == connectionId && !it.retired }
                ?: NotificationSession(connectionId, UUID.randomUUID().toString().replace("-", ""), access(connection)).also {
                    state.sessions += it
                    journal.save(state)
                }
            Triple(connection, session.registrationId, session.cursor)
        } ?: return false
        if (request.third == null) {
            val cursor = try { backend.checkpoint(request.first) }
                catch (failure: InboxFailure) { throw RecoveryRequestFailure(request.second, request.third, failure.problem) }
            locked {
                val state = journal.load()
                reconcile(state, settings.load())
                val session = state.sessions.firstOrNull { it.registrationId == request.second && !it.retired } ?: return@locked
                if (session.cursor == null) { session.cursor = cursor; journal.save(state) }
            }
            return false
        }
        val page = try { backend.changes(request.first, request.third!!) }
            catch (failure: InboxFailure) { throw RecoveryRequestFailure(request.second, request.third, failure.problem) }
        locked {
            val state = journal.load()
            val connections = settings.load()
            reconcile(state, connections)
            val session = state.sessions.firstOrNull { it.registrationId == request.second && !it.retired } ?: return@locked
            if (session.cursor != request.third) return@locked
            val connection = connections.first { it.id == session.connectionId }
            for (change in page.items) {
                if (change.kind == "accepted" && state.entries.none { it.registrationId == session.registrationId && it.alertId == change.alertId }) {
                    state.entries += NotificationEntry(session.registrationId, change.alertId, UUID.randomUUID().toString().replace("-", ""), dismissed = true)
                    if (output.enabled()) session.pendingCount++
                }
            }
            if (!output.enabled()) session.pendingCount = 0
            session.cursor = page.nextCursor
            if (!page.hasMore && session.pendingCount > 0) {
                session.summaryTarget = session.summaryTarget ?: UUID.randomUUID().toString().replace("-", "")
                session.summaryCount = session.pendingCount
                session.pendingCount = 0
                session.summaryDismissed = false
                journal.save(state)
                output.catchUp(CatchUpNotice(session.summaryTarget!!, connection.name, session.summaryCount), true)
            } else journal.save(state)
        }
        return page.hasMore
    }

    suspend fun dismiss(targetId: String) = locked {
        val state = journal.load()
        val entry = state.entries.firstOrNull { it.targetId == targetId }
        val summary = state.sessions.firstOrNull { it.summaryTarget == targetId }
        if (entry == null && summary == null) return@locked
        entry?.dismissed = true
        summary?.summaryDismissed = true
        journal.save(state)
        output.cancel(targetId)
    }

    suspend fun resolveTap(targetId: String): NotificationDestination? = locked {
        val state = journal.load()
        val connections = settings.load()
        reconcile(state, connections)
        val summary = state.sessions.firstOrNull { it.summaryTarget == targetId && !it.retired && it.summaryCount > 0 }
        if (summary != null) {
            summary.summaryDismissed = true
            journal.save(state)
            output.cancel(targetId)
            return@locked NotificationDestination(null, connections.first { it.id == summary.connectionId })
        }
        val entry = state.entries.firstOrNull { it.targetId == targetId } ?: return@locked null
        val session = state.sessions.firstOrNull { it.registrationId == entry.registrationId && !it.retired } ?: return@locked null
        val connection = connections.first { it.id == session.connectionId }
        entry.dismissed = true
        journal.save(state)
        output.cancel(targetId)
        NotificationDestination(AlertKey(connection.id, entry.alertId), connection)
    }

    suspend fun synchronize() = locked {
        val state = journal.load()
        val connections = settings.load()
        reconcile(state, connections)
        for (session in state.sessions.filterNot { it.retired }) {
            val connection = connections.first { it.id == session.connectionId }
            session.summaryTarget?.takeIf { !session.summaryDismissed && output.active(it) }?.let { output.rename(it, connection.name) }
            state.entries.filter { it.registrationId == session.registrationId && !it.dismissed }.forEach {
                if (output.active(it.targetId)) output.rename(it.targetId, connection.name)
            }
        }
    }

    private fun reconcile(state: NotificationState, connections: List<Connection>) {
        var changed = false
        for (session in state.sessions.filterNot { it.retired }) {
            if (connections.none { it.id == session.connectionId && access(it) == session.access }) {
                session.retired = true
                mutableRecoveryProblems.update { it - session.connectionId }
                state.entries.filter { it.registrationId == session.registrationId }.forEach { output.cancel(it.targetId) }
                session.summaryTarget?.let(output::cancel)
                changed = true
            }
        }
        if (changed) journal.save(state)
    }

    private suspend fun <T> locked(operation: suspend () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: InboxFailure) { throw failure }
            catch (_: Exception) { throw InboxFailure(Problem.SETTINGS) }
        }
    }

    private fun access(connection: Connection): String = MessageDigest.getInstance("SHA-256")
        .digest((connection.baseUrl.toString() + "\n" + connection.secret).toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
