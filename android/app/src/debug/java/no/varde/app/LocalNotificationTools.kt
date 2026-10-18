package no.varde.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.varde.app.inbox.Inbox
import no.varde.app.notifications.DebugNotificationDriver
import no.varde.app.notifications.NotificationRuntime

@Composable
fun LocalNotificationTools(inbox: Inbox) {
    val context = LocalContext.current
    val runtime = remember { NotificationRuntime.get(context) }
    val state by inbox.state.collectAsStateWithLifecycle()
    val problem by runtime.problem.collectAsStateWithLifecycle()
    var alertId by remember { mutableStateOf("") }
    Column {
        Text("Local notification test (debug only)")
        Text("Use synthetic alerts only. This simulates a signal; it does not test FCM or background recovery.")
        Text("Leave the ID blank to use the first loaded alert from each connection.")
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("Android notification settings") }
        OutlinedTextField(value = alertId, onValueChange = { alertId = it }, label = { Text("Synthetic alert ID") }, singleLine = true)
        state.connections.forEach { connection ->
            val selected = alertId.ifBlank { state.items.firstOrNull { it.key.connectionId == connection.id }?.key?.alertId.orEmpty() }
            Button(enabled = selected.matches(Regex("alert_[A-Za-z0-9_-]{32}")), onClick = {
                runtime.launch { DebugNotificationDriver.signal(context, connection.id, selected) }
            }) { Text("Send local signal for ${connection.name}") }
        }
        problem?.let { Text("Local notification metadata is unavailable. Inbox reads remain available.") }
    }
}
