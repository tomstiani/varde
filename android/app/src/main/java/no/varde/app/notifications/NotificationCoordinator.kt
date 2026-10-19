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
    private val registrationLocks = ConcurrentHashMap<String, Mutex>()
    private val registrationProblems = mutableMapOf<String, Problem>()
    private val locallyMuted = mutableSetOf<String>()
    private val mutableNotificationControls = MutableStateFlow<Map<String, NotificationControl>>(emptyMap())
    val notificationControls = mutableNotificationControls.asStateFlow()
    private val mutableRecoveryProblems = MutableStateFlow<Map<String, Problem>>(emptyMap())
    val recoveryProblems = mutableRecoveryProblems.asStateFlow()
    private val mutex = locks.getOrPut(directory.canonicalPath) { Mutex() }

    suspend fun prepareNotifications() = locked {
        val connections = settings.load()
        val state = journal.load()
        val retired = mutableListOf<NotificationSession>()
        connections.forEach { connection ->
            if (state.preferences.none { it.connectionId == connection.id }) {
                state.preferences += NotificationPreference(connection.id, permissionAllowed = output.enabled())
                state.sessions.filter { it.connectionId == connection.id && !it.retired }.forEach {
                    retireSession(it)
                    retired += it
                }
            }
        }
        state.preferences.removeAll { preference -> connections.none { it.id == preference.connectionId } }
        reconcile(state, connections)
        journal.save(state)
        retired.forEach { cancelSession(state, it) }
        publishControls(state)
    }

    suspend fun reservePermissionPrompt(): Boolean = locked {
        val state = journal.load()
        if (state.permissionRequested) return@locked false
        state.permissionRequested = true
        journal.save(state)
        publishControls(state)
        true
    }

    suspend fun setNotificationsEnabled(connectionId: String, enabled: Boolean) = locked {
        val connections = settings.load()
        val connection = connections.firstOrNull { it.id == connectionId } ?: return@locked
        val state = journal.load()
        reconcile(state, connections)
        val existing = state.preferences.firstOrNull { it.connectionId == connectionId }
        val preference = existing ?: NotificationPreference(connection.id, permissionAllowed = output.enabled()).also { state.preferences += it }
        if (existing != null && preference.enabled == enabled && connectionId !in locallyMuted) return@locked
        preference.enabled = enabled
        val retired = state.sessions.filter { it.connectionId == connectionId && !it.retired }
        retired.forEach(::retireSession)
        if (!enabled) locallyMuted += connectionId
        try {
            journal.save(state)
            if (enabled) locallyMuted -= connectionId
            registrationProblems -= connectionId
        } catch (failure: Exception) {
            registrationProblems[connectionId] = Problem.SETTINGS
            throw failure
        } finally {
            retired.forEach { cancelSession(state, it) }
            publishControls(state)
        }
    }

    suspend fun unregisterObsolete(previousConnections: List<Connection>) = coroutineScope {
        val removals = locked {
            val state = journal.load()
            reconcile(state, settings.load())
            state.sessions.asReversed().filter { it.retired && it.registration?.removalComplete == false }.mapNotNull { session ->
                previousConnections.firstOrNull { it.id == session.connectionId && access(it) == session.access }
                    ?.let { it to session.registrationId }
            }
        }
        removals.take(4).forEach { (connection, registrationId) -> launch {
            registrationLocks.getOrPut(connection.id) { Mutex() }.withLock { removeRegistration(connection, registrationId) }
        } }
    }

    private suspend fun removeRegistration(connection: Connection, registrationId: String) {
        try {
            val current = backend.pushRegistration(connection)
            if (current.registrationId == registrationId) backend.deletePush(connection, PushMutation(current.version, randomIdentifier()))
            locked {
                val state = journal.load()
                state.sessions.firstOrNull { it.registrationId == registrationId && it.retired }?.registration?.let {
                    it.removalComplete = true
                    journal.save(state)
                }
            }
        } catch (_: InboxFailure) { }
    }

    private fun retireSession(session: NotificationSession) {
        session.retired = true
        session.registration?.let { it.ready = false; it.key = null; it.provider = null; it.destination = null }
    }

    private fun cancelSession(state: NotificationState, session: NotificationSession) {
        state.entries.filter { it.registrationId == session.registrationId }.forEach { output.cancel(it.targetId) }
        session.summaryTarget?.let(output::cancel)
    }

    private fun eligible(state: NotificationState, session: NotificationSession): Boolean =
        output.enabled() && session.connectionId !in locallyMuted && state.preferences.none { it.connectionId == session.connectionId && !it.enabled } && session.registration?.ready != false

    suspend fun register(connectionId: String, token: PushToken?) = registrationLocks.getOrPut(connectionId) { Mutex() }.withLock {
        repeat(2) {
            var request = locked {
                val connections = settings.load()
                val state = journal.load()
                reconcile(state, connections)
                val connection = connections.firstOrNull { it.id == connectionId } ?: return@locked null
                val preference = state.preferences.firstOrNull { it.connectionId == connectionId } ?: return@locked null
                if (!preference.enabled || !output.enabled() || connectionId in locallyMuted) return@locked null
                var session = state.sessions.firstOrNull { it.connectionId == connectionId && !it.retired }
                val replacedFixture = session?.takeIf { it.registration == null }
                if (session != null && session.registration == null && token == null) return@locked null
                if (session?.registration == null) {
                    session?.let(::retireSession)
                    session = NotificationSession(connectionId, randomIdentifier(), access(connection), registration = RegistrationState())
                    state.sessions += session
                }
                val remote = session.registration!!
                if (remote.key == null) {
                    if (token == null) {
                        journal.save(state)
                        publishControls(state)
                        return@locked null
                    }
                    if (!remote.ready || remote.provider != token.provider || remote.destination != token.destination) {
                        PushDestination(session.registrationId, token.provider, token.destination)
                        remote.provider = token.provider
                        remote.destination = token.destination
                        remote.key = randomIdentifier()
                        remote.version = null
                    }
                }
                journal.save(state)
                replacedFixture?.let { cancelSession(state, it) }
                publishControls(state)
                RegistrationRequest(connection, session.registrationId, remote.key, remote.version,
                    PushDestination(session.registrationId, remote.provider!!, remote.destination!!), remote.baseline)
            } ?: return@withLock
            try {
                request = reconcileRegistration(request) ?: return@withLock
                val version = request.version!!
                val current = locked {
                    val state = journal.load()
                    reconcile(state, settings.load())
                    val session = state.sessions.firstOrNull { it.registrationId == request.registrationId && !it.retired } ?: return@locked false
                    if (!output.enabled()) return@locked false
                    if (session.registration!!.key != request.key) return@locked false
                    session.registration!!.version = version
                    journal.save(state)
                    true
                }
                if (!current) return@withLock
                val result = try {
                    backend.putPush(request.connection, PushMutation(version, request.key!!), request.destination)
                } catch (failure: InboxFailure) {
                    if (failure.problem != Problem.REGISTRATION_CHANGED) throw failure
                    reconcileRegistration(request, rejected = true) ?: return@withLock
                    return@repeat
                }
                val applied = locked {
                    val state = journal.load()
                    reconcile(state, settings.load())
                    val session = state.sessions.firstOrNull { it.registrationId == request.registrationId && !it.retired } ?: return@locked false
                    val remote = session.registration!!
                    if (remote.key != request.key) return@locked true
                    if (result.registrationId != session.registrationId || !result.enabled || result.baselineCursor == null ||
                        (remote.baseline != null && remote.baseline != result.baselineCursor)) throw InboxFailure(Problem.RESPONSE)
                    if (session.cursor == null) session.cursor = result.baselineCursor
                    remote.ready = true
                    remote.baseline = result.baselineCursor
                    remote.version = result.version
                    remote.key = null
                    registrationProblems -= connectionId
                    journal.save(state)
                    publishControls(state)
                    true
                }
                if (!applied) {
                    removeRegistration(request.connection, request.registrationId)
                    return@withLock
                }
                if (token == null || (request.destination.provider == token.provider && request.destination.destination == token.destination)) return@withLock
            } catch (failure: InboxFailure) {
                locked {
                    val state = journal.load()
                    reconcile(state, settings.load())
                    if (state.sessions.any { it.registrationId == request.registrationId && !it.retired }) {
                        registrationProblems[connectionId] = failure.problem
                        if (failure.problem in setOf(Problem.REGISTRATION_CHANGED, Problem.IDEMPOTENCY_CONFLICT, Problem.RECOVERY_CURSOR, Problem.AUTHENTICATION, Problem.PERMISSION, Problem.RESPONSE)) {
                            val session = state.sessions.first { it.registrationId == request.registrationId }
                            retireSession(session)
                            journal.save(state)
                            cancelSession(state, session)
                        }
                        publishControls(state)
                    }
                }
                return@withLock
            }
        }
    }

    private suspend fun reconcileRegistration(request: RegistrationRequest, rejected: Boolean = false): RegistrationRequest? {
        // A bound, uncertain mutation must replay before observing a newer precondition.
        if (!rejected && request.key != null && request.version != null) return request
        val current = backend.pushRegistration(request.connection)
        if (request.baseline != null && current.enabled) {
            if (current.registrationId != request.registrationId) throw InboxFailure(Problem.REGISTRATION_CHANGED)
            if (current.baselineCursor != request.baseline) throw InboxFailure(Problem.RECOVERY_CURSOR)
        }
        // Rejection at the observed current version fences a genuinely retired session ID.
        if (rejected && current.version == request.version) throw InboxFailure(Problem.REGISTRATION_CHANGED)
        return locked {
            val state = journal.load()
            reconcile(state, settings.load())
            val session = state.sessions.firstOrNull { it.registrationId == request.registrationId && !it.retired } ?: return@locked null
            val remote = session.registration!!
            if (session.connectionId in locallyMuted || !output.enabled() || remote.key != request.key) return@locked null
            if (!rejected && request.key == null && current.enabled && current.version == request.version) {
                registrationProblems -= session.connectionId
                publishControls(state)
                return@locked null
            }
            if (!current.enabled) remote.ready = false
            remote.key = if (rejected || request.key == null) randomIdentifier() else request.key
            remote.version = current.version
            if (rejected) registrationProblems[session.connectionId] = Problem.REGISTRATION_CHANGED
            journal.save(state)
            publishControls(state)
            RegistrationRequest(request.connection, request.registrationId, remote.key, remote.version, request.destination, request.baseline)
        }
    }

    private class RegistrationRequest(val connection: Connection, val registrationId: String, val key: String?,
        val version: Long?, val destination: PushDestination, val baseline: String?)

    private fun publishControls(state: NotificationState) {
        mutableNotificationControls.value = state.preferences.associate { preference ->
            val ready = state.sessions.any { it.connectionId == preference.connectionId && !it.retired && it.registration?.ready == true }
            val activation = when {
                !preference.enabled || preference.connectionId in locallyMuted -> NotificationActivation.MUTED
                !output.enabled() -> NotificationActivation.PERMISSION_REQUIRED
                ready -> NotificationActivation.ACTIVE
                else -> NotificationActivation.PENDING
            }
            preference.connectionId to NotificationControl(preference.enabled && preference.connectionId !in locallyMuted, activation, registrationProblems[preference.connectionId], !state.permissionRequested)
        }
    }

    private fun randomIdentifier() = UUID.randomUUID().toString().replace("-", "")

    suspend fun activate(connectionId: String, registrationId: String) = locked {
        val connection = settings.load().firstOrNull { it.id == connectionId } ?: return@locked
        require(registrationId.matches(Regex("[0-9a-f]{32}")))
        val state = journal.load()
        reconcile(state, settings.load())
        if (connectionId in locallyMuted || state.preferences.any { it.connectionId == connectionId && (!it.enabled || !output.enabled()) }) return@locked
        val existing = state.sessions.firstOrNull { it.registrationId == registrationId }
        if (existing != null) {
            if (existing.retired || existing.connectionId != connectionId || existing.access != access(connection)) {
                throw InboxFailure(Problem.CONFIGURATION)
            }
            return@locked
        }
        val retired = state.sessions.filter { it.connectionId == connectionId && !it.retired }
        retired.forEach(::retireSession)
        state.sessions += NotificationSession(connectionId, registrationId, access(connection))
        journal.save(state)
        state.entries.filter { entry -> retired.any { it.registrationId == entry.registrationId } }.forEach { output.cancel(it.targetId) }
        retired.forEach { it.summaryTarget?.let(output::cancel) }
    }

    suspend fun currentRegistration(connectionId: String, includePending: Boolean = true): String? = locked {
        val state = journal.load()
        reconcile(state, settings.load())
        state.sessions.firstOrNull { it.connectionId == connectionId && !it.retired &&
            (includePending || it.registration?.ready != false) && connectionId !in locallyMuted }?.registrationId
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
            if (session.connectionId in locallyMuted || session.registration?.ready == false || state.preferences.any { it.connectionId == session.connectionId && !it.enabled }) return@locked null
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
                if (!entry.dismissed && eligible(state, session) && output.active(entry.targetId)) {
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
                        retireSession(session)
                        journal.save(state)
                        cancelSession(state, session)
                        publishControls(state)
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
                    if (state.preferences.any { preference -> preference.connectionId == connectionId }) it.registration = RegistrationState()
                    state.sessions += it
                    journal.save(state)
                }
            // Explicit local fixtures cannot initialize automatic recovery.
            if (session.registration == null && state.preferences.any { it.connectionId == connectionId }) return@locked null
            if (session.registration?.ready == false && output.enabled() && state.preferences.any { it.connectionId == connectionId && it.enabled }) return@locked null
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
                    if (eligible(state, session)) session.pendingCount++
                }
            }
            if (!eligible(state, session)) session.pendingCount = 0
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
            if (summary.connectionId in locallyMuted) return@locked null
            summary.summaryDismissed = true
            journal.save(state)
            output.cancel(targetId)
            return@locked NotificationDestination(null, connections.first { it.id == summary.connectionId })
        }
        val entry = state.entries.firstOrNull { it.targetId == targetId } ?: return@locked null
        val session = state.sessions.firstOrNull { it.registrationId == entry.registrationId && !it.retired } ?: return@locked null
        if (session.connectionId in locallyMuted) return@locked null
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
        val retired = mutableListOf<NotificationSession>()
        if (state.preferences.removeAll { preference -> connections.none { it.id == preference.connectionId } }) changed = true
        for (preference in state.preferences) {
            if (preference.permissionAllowed != output.enabled()) {
                preference.permissionAllowed = output.enabled()
                state.sessions.filter { it.connectionId == preference.connectionId && !it.retired }.forEach {
                    retireSession(it)
                    retired += it
                }
                changed = true
            }
        }
        for (session in state.sessions.filterNot { it.retired }) {
            if (connections.none { it.id == session.connectionId && access(it) == session.access }) {
                retireSession(session)
                retired += session
                mutableRecoveryProblems.update { it - session.connectionId }
                changed = true
            }
        }
        if (changed) journal.save(state)
        retired.forEach { cancelSession(state, it) }
        publishControls(state)
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
