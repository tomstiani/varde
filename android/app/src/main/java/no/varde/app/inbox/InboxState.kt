package no.varde.app.inbox

data class DetailState(
    val key: AlertKey,
    val alert: AlertDetail? = null,
    val loading: Boolean = true,
    val problem: Problem? = null,
    val sourceProblem: Problem? = null,
) {
    val stale: Boolean get() = alert != null && (problem != null || sourceProblem != null)
}

data class InboxState(
    val initialized: Boolean = false,
    val connections: List<Connection> = emptyList(),
    val items: List<InboxItem> = emptyList(),
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    val historyComplete: Boolean = false,
    val refreshRevision: Int = 0,
    val saving: Boolean = false,
    val problems: Map<String, Problem> = emptyMap(),
    val settingsProblem: Problem? = null,
    val staleConnections: Set<String> = emptySet(),
    val detail: DetailState? = null,
) {
    fun connection(id: String) = connections.find { it.id == id }
}
