package no.varde.app.inbox

data class Presentation(val state: String, val revision: Int)

data class AlertRow(
    val id: String,
    val acceptedAt: String,
    val title: String,
    val priority: String,
    val observedAt: String,
    val presentation: Presentation,
)

data class AlertDetail(val row: AlertRow, val summary: String)
data class HistoryPage(val items: List<AlertRow>, val nextCursor: String? = null)
data class AlertKey(val connectionId: String, val alertId: String)
data class InboxItem(val key: AlertKey, val row: AlertRow)

interface Backend {
    suspend fun history(connection: Connection, cursor: String? = null): HistoryPage
    suspend fun read(connection: Connection, id: String): AlertDetail
}

interface ConnectionSettings {
    suspend fun load(): List<Connection>
    suspend fun save(connections: List<Connection>)
}
