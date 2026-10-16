package no.varde.app.inbox

import com.ibm.icu.lang.UCharacter

internal fun changedConnections(
    existing: List<Connection>,
    id: String?,
    name: String,
    url: String,
    secret: String,
): Pair<List<Connection>, Connection> {
    val previous = id?.let { key -> existing.find { it.id == key } ?: throw InboxFailure(Problem.CONFIGURATION) }
    val credential = secret.ifEmpty { previous?.secret.orEmpty() }
    val changed = if (previous == null) Connection.create(name, url, credential)
        else Connection.create(name, url, credential, previous.id)
    // A new destination requires deliberate credential entry, never silent forwarding.
    if (previous != null && previous.baseUrl != changed.baseUrl && secret.isEmpty()) {
        throw InboxFailure(Problem.CONFIGURATION)
    }
    validateConnections(existing.filterNot { it.id == id } + changed)
    val updated = if (previous == null) existing + changed else existing.map { if (it.id == id) changed else it }
    return updated to changed
}

internal fun validateConnections(connections: List<Connection>) {
    if (connections.map { it.id }.distinct().size != connections.size) throw InboxFailure(Problem.SETTINGS)
    val names = connections.map { UCharacter.foldCase(it.name, true) }
    if (names.distinct().size != names.size) throw InboxFailure(Problem.DUPLICATE_NAME)
    if (connections.map { it.baseUrl }.distinct().size != connections.size) throw InboxFailure(Problem.DUPLICATE_URL)
}

internal fun Connection.sameAccess(other: Connection): Boolean =
    id == other.id && baseUrl == other.baseUrl && secret == other.secret
