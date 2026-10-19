package no.varde.app.notifications

import no.varde.app.inbox.Problem

enum class NotificationActivation { MUTED, PERMISSION_REQUIRED, PENDING, ACTIVE }
data class NotificationControl(val enabled: Boolean, val activation: NotificationActivation, val problem: Problem? = null,
    val permissionPromptAvailable: Boolean = true)
class PushToken(val provider: String, val destination: String)
fun interface PushTokenSource { suspend fun current(): PushToken? }

internal class NotificationPreference(val connectionId: String, var enabled: Boolean = true, var permissionAllowed: Boolean)
internal class RegistrationState(
    var ready: Boolean = false,
    var baseline: String? = null,
    var version: Long? = null,
    var key: String? = null,
    var provider: String? = null,
    var destination: String? = null,
    var removalComplete: Boolean = false,
)
