package no.varde.app.inbox

import java.nio.charset.StandardCharsets

data class PushRegistration(
    val registrationId: String?,
    val provider: String?,
    val enabled: Boolean,
    val baselineCursor: String?,
    val version: Long,
) {
    override fun toString() = "PushRegistration(enabled=$enabled, version=$version)"
}

// Avoid automatic diagnostic strings containing mutation keys or private destinations.
class PushMutation(val version: Long, val key: String) {
    init { require(version >= 0 && key.matches(Regex("[0-9a-f]{32}"))) }
}

class PushDestination(val registrationId: String, val provider: String, val destination: String) {
    init {
        require(registrationId.matches(Regex("[0-9a-f]{32}")) && provider == "fcm")
        require(destination.isNotEmpty() && StandardCharsets.UTF_8.newEncoder().canEncode(destination))
        require(destination.toByteArray(Charsets.UTF_8).size <= 8_192)
    }
}
