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

interface Backend {
    suspend fun history(connection: Connection): List<AlertRow>
    suspend fun read(connection: Connection, id: String): AlertDetail
}

interface ConnectionSettings {
    suspend fun load(): Connection?
    suspend fun save(connection: Connection)
}
