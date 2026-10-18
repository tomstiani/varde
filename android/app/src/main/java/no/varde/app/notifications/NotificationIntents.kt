package no.varde.app.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import no.varde.app.MainActivity

object NotificationIntents {
    private const val OPEN = "no.varde.app.OPEN_ALERT"
    private const val DISMISS = "no.varde.app.DISMISS_ALERT"

    fun open(context: Context, targetId: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(OPEN).setData(address(targetId))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun content(context: Context, targetId: String): PendingIntent = PendingIntent.getActivity(
        context, 0, open(context, targetId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun dismissal(context: Context, targetId: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(DISMISS).setClassName(context, "no.varde.app.notifications.NotificationDismissReceiver").setData(address(targetId)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun openTarget(intent: Intent): String? = target(intent, OPEN)
    fun dismissTarget(intent: Intent): String? = target(intent, DISMISS)

    private fun address(targetId: String): Uri {
        require(targetId.matches(Regex("[0-9a-f]{32}")))
        return Uri.parse("varde://notification/$targetId")
    }

    private fun target(intent: Intent, action: String): String? {
        if (intent.action != action) return null
        val id = intent.data?.lastPathSegment ?: return null
        if (!id.matches(Regex("[0-9a-f]{32}")) || intent.data != address(id)) return null
        return id
    }
}
