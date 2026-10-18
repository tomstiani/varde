package no.varde.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = NotificationIntents.dismissTarget(intent) ?: return
        val pending = goAsync()
        NotificationRuntime.get(context).launch {
            try { NotificationRuntime.get(context).coordinator.dismiss(target) } finally { pending.finish() }
        }
    }
}
