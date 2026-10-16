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
    private val detailConnectionFailures = mutableMapOf<String, Problem>()
    private val backend = BoundedBackend(backend) { detailConnectionFailures[it] }
    private val mutableState = MutableStateFlow(InboxState())
    val state = mutableState.asStateFlow()
    private var historyJob: Job? = null
    private var detailJob: Job? = null
    private val loadedDetails = mutableMapOf<AlertKey, AlertDetail>()
    private var historySession: HistorySession? = null
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
                    loadedDetails.keys.removeAll { it.connectionId == affectedId }
                    historyRevision++
                    historyJob?.cancel()
                    historySession = null
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
                        loading = if (reload) false else it.loading,
                        loadingOlder = if (reload) false else it.loadingOlder,
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
        if (state.value.loading) return
        historyJob?.cancel()
        val requestRevision = ++historyRevision
        detailConnectionFailures.clear()
        val connections = state.value.connections
        historySession = null
        if (connections.isEmpty()) {
            mutableState.update { it.copy(loading = false, loadingOlder = false, hasMore = false, historyComplete = false, items = emptyList(), problems = emptyMap(), staleConnections = emptySet()) }
            return
        }
        mutableState.update { it.copy(loading = true, loadingOlder = false) }
        historyJob = scope.launch {
            val batch = HistorySession.begin(connections).nextBatch(backend)
            if (historyRevision == requestRevision) {
                val result = applyDetailFailures(batch)
                historySession = result.session
                mutableState.update {
                    val retained = it.items.filter { item -> item.key.connectionId in result.problems }
                    it.copy(
                        items = (result.items + retained).distinctBy { item -> item.key }.sortedWith(inboxOrdering),
                        loading = false,
                        hasMore = result.session.hasMore,
                        historyComplete = result.session.complete,
                        refreshRevision = it.refreshRevision + 1,
                        detail = it.detail?.let { detail -> detail.copy(sourceProblem = result.problems[detail.key.connectionId]) },
                        problems = result.problems,
                        staleConnections = result.problems.keys,
                    )
                }
            }
        }
    }

    fun retryConnection(id: String) {
        if (state.value.connection(id) != null && id in state.value.problems) refresh()
    }

    fun loadOlder() {
        val current = state.value
        val session = historySession ?: return
        if (current.loading || current.loadingOlder || !current.hasMore) return
        val requestRevision = ++historyRevision
        mutableState.update { it.copy(loadingOlder = true) }
        historyJob = scope.launch {
            val batch = session.nextBatch(backend)
            if (historyRevision == requestRevision) {
                val result = applyDetailFailures(batch)
                historySession = result.session
                mutableState.update {
                    it.copy(
                        items = (it.items + result.items).distinctBy { item -> item.key }.sortedWith(inboxOrdering),
                        loadingOlder = false,
                        hasMore = result.session.hasMore,
                        historyComplete = result.session.complete,
                        detail = it.detail?.let { detail -> detail.copy(sourceProblem = result.problems[detail.key.connectionId]) },
                        problems = result.problems,
                        staleConnections = result.problems.keys,
                    )
                }
            }
        }
    }

    private fun applyDetailFailures(batch: HistoryBatch) = batch.copy(
        items = batch.items.filterNot { it.key.connectionId in detailConnectionFailures },
        problems = batch.problems + detailConnectionFailures,
        session = batch.session.withFailures(detailConnectionFailures, historySession),
    )

    private fun pauseHistoryFromDetail(connectionId: String, problem: Problem) {
        detailConnectionFailures[connectionId] = problem
        historySession = historySession?.withFailures(detailConnectionFailures)
        mutableState.update {
            it.copy(
                problems = it.problems + (connectionId to problem),
                staleConnections = it.staleConnections + connectionId,
                hasMore = historySession?.hasMore ?: it.hasMore,
                historyComplete = false,
                detail = it.detail?.let { detail -> if (detail.key.connectionId == connectionId) detail.copy(sourceProblem = problem) else detail },
            )
        }
    }

    fun open(key: AlertKey) = openDetail(key, retry = false)

    fun retryDetail() { state.value.detail?.key?.let { openDetail(it, retry = true) } }

    private fun openDetail(key: AlertKey, retry: Boolean) {
        val connection = state.value.connection(key.connectionId) ?: return
        if (state.value.items.none { it.key == key } && state.value.detail?.key != key) return
        detailJob?.cancel()
        val requestRevision = ++detailRevision
        val retained = loadedDetails[key]
        val sourceProblem = state.value.problems[key.connectionId]
        mutableState.update { it.copy(detail = DetailState(key, retained, loading = sourceProblem == null || retry, sourceProblem = sourceProblem)) }
        if (sourceProblem != null && !retry) return
        detailJob = scope.launch {
            try {
                val alert = backend.read(connection, key.alertId)
                if (detailRequestIsCurrent(key, connection, requestRevision)) {
                    loadedDetails[key] = alert
                    mutableState.update { it.copy(detail = DetailState(key, alert, loading = false, sourceProblem = it.problems[key.connectionId])) }
                }
            } catch (failure: InboxFailure) {
                if (detailRequestIsCurrent(key, connection, requestRevision)) {
                    if (failure.problem.affectsConnection) pauseHistoryFromDetail(key.connectionId, failure.problem)
                    mutableState.update { it.copy(detail = DetailState(key, retained, false, failure.problem, it.problems[key.connectionId])) }
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
