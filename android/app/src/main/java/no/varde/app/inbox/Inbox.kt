package no.varde.app.inbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val id: String,
    val alert: AlertDetail? = null,
    val loading: Boolean = true,
    val problem: Problem? = null,
    val stale: Boolean = false,
)

data class InboxState(
    val initialized: Boolean = false,
    val connection: Connection? = null,
    val items: List<AlertRow> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val problem: Problem? = null,
    val settingsProblem: Problem? = null,
    val stale: Boolean = false,
    val detail: DetailState? = null,
)

class Inbox(
    private val scope: CoroutineScope,
    private val settings: ConnectionSettings,
    private val backend: Backend,
) {
    private val mutableState = MutableStateFlow(InboxState())
    val state = mutableState.asStateFlow()
    private var historyJob: Job? = null
    private var detailJob: Job? = null
    private var generation = 0
    private var historyRevision = 0
    private var detailRevision = 0

    init {
        scope.launch {
            try {
                val connection = settings.load()
                mutableState.update { it.copy(initialized = true, connection = connection) }
                refresh()
            } catch (failure: InboxFailure) {
                mutableState.update { it.copy(initialized = true, settingsProblem = failure.problem) }
            }
        }
    }

    fun save(name: String, url: String, secret: String, onSaved: () -> Unit) {
        if (state.value.saving) return
        val connection = try {
            Connection.create(name, url, secret, state.value.connection?.id ?: java.util.UUID.randomUUID().toString())
        } catch (failure: InboxFailure) {
            mutableState.update { it.copy(settingsProblem = failure.problem) }
            return
        }
        mutableState.update { it.copy(saving = true, settingsProblem = null) }
        scope.launch {
            try {
                settings.save(connection)
                generation++
                historyJob?.cancel()
                detailJob?.cancel()
                mutableState.value = InboxState(initialized = true, connection = connection)
                refresh()
                onSaved()
            } catch (failure: InboxFailure) {
                mutableState.update { it.copy(saving = false, settingsProblem = failure.problem) }
            }
        }
    }

    fun refresh() {
        val connection = state.value.connection ?: return
        historyJob?.cancel()
        val requestGeneration = generation
        val requestRevision = ++historyRevision
        mutableState.update { it.copy(loading = true, problem = null) }
        historyJob = scope.launch {
            try {
                val items = backend.history(connection)
                if (generation == requestGeneration && historyRevision == requestRevision) {
                    mutableState.update { it.copy(items = items, loading = false, problem = null, stale = false) }
                }
            } catch (failure: InboxFailure) {
                if (generation == requestGeneration && historyRevision == requestRevision) {
                    mutableState.update { it.copy(loading = false, problem = failure.problem, stale = it.items.isNotEmpty()) }
                }
            }
        }
    }

    fun open(id: String) {
        val connection = state.value.connection ?: return
        if (state.value.items.none { it.id == id }) return
        detailJob?.cancel()
        val requestGeneration = generation
        val requestRevision = ++detailRevision
        val retained = state.value.detail?.takeIf { it.id == id }?.alert
        mutableState.update { it.copy(detail = DetailState(id, retained)) }
        detailJob = scope.launch {
            try {
                val alert = backend.read(connection, id)
                if (generation == requestGeneration && detailRevision == requestRevision && state.value.detail?.id == id) {
                    mutableState.update { it.copy(detail = DetailState(id, alert, loading = false)) }
                }
            } catch (failure: InboxFailure) {
                if (generation == requestGeneration && detailRevision == requestRevision && state.value.detail?.id == id) {
                    mutableState.update { it.copy(detail = DetailState(id, retained, false, failure.problem, retained != null)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    fun closeDetail() {
        detailRevision++
        detailJob?.cancel()
        mutableState.update { it.copy(detail = null) }
    }
}
