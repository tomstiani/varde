package no.varde.app.inbox

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@MainThread
class Inbox(
    private val scope: CoroutineScope,
    private val settings: ConnectionSettings,
    backend: Backend,
) {
    private val backend = BoundedBackend(backend)
    private val mutableState = MutableStateFlow(InboxState())
    val state = mutableState.asStateFlow()
    private var historyJob: Job? = null
    private var detailJob: Job? = null
    private var historyRevision = 0
    private var detailRevision = 0

    init {
        scope.launch {
            try {
                val connections = settings.load()
                validateConnections(connections)
                mutableState.update { it.copy(initialized = true, connections = connections) }
                refresh()
            } catch (failure: InboxFailure) {
                mutableState.update { it.copy(initialized = true, settingsProblem = failure.problem) }
            }
        }
    }

    fun beginConnectionEdit() {
        mutableState.update { it.copy(settingsProblem = null) }
    }

    fun save(id: String?, name: String, url: String, secret: String, onSaved: () -> Unit) {
        if (!state.value.initialized || state.value.saving) return
        try {
            val (updated, changed) = changedConnections(state.value.connections, id, name, url, secret)
            val previous = state.value.connection(changed.id)
            persistConnections(updated, changed.id, previous == null || !previous.sameAccess(changed), onSaved)
        } catch (failure: InboxFailure) {
            mutableState.update { it.copy(settingsProblem = failure.problem) }
        }
    }

    fun remove(id: String, onRemoved: () -> Unit) {
        if (!state.value.initialized || state.value.saving) return
        if (state.value.connection(id) == null) return
        persistConnections(state.value.connections.filterNot { it.id == id }, id, true, onRemoved)
    }

    private fun persistConnections(updated: List<Connection>, affectedId: String, reload: Boolean, onSaved: () -> Unit) {
        mutableState.update { it.copy(saving = true, settingsProblem = null) }
        scope.launch {
            try {
                settings.save(updated)
                if (reload) {
                    historyRevision++
                    historyJob?.cancel()
                    if (state.value.detail?.key?.connectionId == affectedId) {
                        detailRevision++
                        detailJob?.cancel()
                    }
                }
                mutableState.update {
                    it.copy(
                        connections = updated,
                        items = if (reload) it.items.filterNot { item -> item.key.connectionId == affectedId } else it.items,
                        detail = if (reload && it.detail?.key?.connectionId == affectedId) null else it.detail,
                        problems = if (reload) it.problems - affectedId else it.problems,
                        staleConnections = if (reload) it.staleConnections - affectedId else it.staleConnections,
                        saving = false,
                    )
                }
                if (reload) refresh()
                onSaved()
            } catch (failure: InboxFailure) {
                mutableState.update { it.copy(saving = false, settingsProblem = failure.problem) }
            }
        }
    }

    fun refresh() {
        historyJob?.cancel()
        val requestRevision = ++historyRevision
        val connections = state.value.connections
        if (connections.isEmpty()) {
            mutableState.update { it.copy(loading = false, items = emptyList(), problems = emptyMap(), staleConnections = emptySet()) }
            return
        }
        mutableState.update { it.copy(loading = true) }
        historyJob = scope.launch {
            val result = loadNewest(connections, backend)
            if (historyRevision == requestRevision) {
                mutableState.update {
                    val retained = it.items.filter { item -> item.key.connectionId in result.problems }
                    it.copy(
                        items = (result.items + retained).distinctBy { item -> item.key }.sortedWith(inboxOrdering),
                        loading = false,
                        problems = result.problems,
                        staleConnections = result.problems.keys,
                    )
                }
            }
        }
    }

    fun open(key: AlertKey) {
        val connection = state.value.connection(key.connectionId) ?: return
        if (state.value.items.none { it.key == key } && state.value.detail?.key != key) return
        detailJob?.cancel()
        val requestRevision = ++detailRevision
        val retained = state.value.detail?.takeIf { it.key == key }?.alert
        mutableState.update { it.copy(detail = DetailState(key, retained)) }
        detailJob = scope.launch {
            try {
                val alert = backend.read(connection, key.alertId)
                if (detailRequestIsCurrent(key, connection, requestRevision)) {
                    mutableState.update { it.copy(detail = DetailState(key, alert, loading = false)) }
                }
            } catch (failure: InboxFailure) {
                if (detailRequestIsCurrent(key, connection, requestRevision)) {
                    mutableState.update { it.copy(detail = DetailState(key, retained, false, failure.problem, retained != null)) }
                }
            }
        }
    }

    private fun detailRequestIsCurrent(key: AlertKey, connection: Connection, revision: Int) =
        detailRevision == revision && state.value.detail?.key == key &&
            state.value.connection(key.connectionId)?.sameAccess(connection) == true

    fun closeDetail() {
        detailRevision++
        detailJob?.cancel()
        mutableState.update { it.copy(detail = null) }
    }
}
