package no.varde.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class AndroidNotificationOutput(private val context: Context) : NotificationOutput {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Alert notifications", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun enabled(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    override fun show(notice: AlertNotice, interrupt: Boolean) {
        if (!enabled()) return
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notice.detail?.row?.title ?: "New alert from ${notice.source}")
            .setContentText(notice.detail?.summary ?: "Open Varde to fetch alert details.")
            .setSubText(notice.source)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(notice.source))
            .setOnlyAlertOnce(!interrupt)
            .setAutoCancel(true)
            .setContentIntent(NotificationIntents.content(context, notice.targetId))
            .setDeleteIntent(NotificationIntents.dismissal(context, notice.targetId))
        notice.detail?.let { notification.setStyle(Notification.BigTextStyle().bigText(it.summary)) }
        notification.extras.putBoolean("varde.generic", notice.detail == null)
        manager.notify(tag(notice.targetId), 1, notification.build())
    }

    override fun active(targetId: String): Boolean = manager.activeNotifications.any { it.tag == tag(targetId) && it.id == 1 }
    override fun cancel(targetId: String) { manager.cancel(tag(targetId), 1) }

    override fun rename(targetId: String, source: String) {
        val current = manager.activeNotifications.firstOrNull { it.tag == tag(targetId) && it.id == 1 } ?: return
        val builder = Notification.Builder.recoverBuilder(context, current.notification)
            .setSubText(source).setPublicVersion(publicVersion(source)).setOnlyAlertOnce(true)
        if (current.notification.extras.getBoolean("varde.generic")) builder.setContentTitle("New alert from $source")
        manager.notify(tag(targetId), 1, builder.build())
    }

    private fun publicVersion(source: String): Notification = Notification.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("New alert from $source")
        .setContentText("Unlock to view alert details.")
        .build()

    private fun tag(targetId: String) = "varde.alert.$targetId"

    companion object { private const val CHANNEL = "alerts" }
}
