package no.varde.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import no.varde.app.notifications.NotificationControl
import no.varde.app.notifications.NotificationActivation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.InboxState
import no.varde.app.inbox.Problem

@Composable
fun ConnectionsScreen(state: InboxState, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit,
    tools: @Composable () -> Unit = {}, recoveryProblems: Map<String, Problem> = emptyMap(), onRecoveryRetry: (String) -> Unit = {},
    notificationControls: Map<String, NotificationControl> = emptyMap(),
    onNotificationToggle: (String, Boolean) -> Unit = { _, _ -> }, onNotificationPermission: () -> Unit = {}, onNotificationRetry: (String) -> Unit = {}) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Connections")
            Button(onClick = onAdd, enabled = !state.saving) { Text("Add connection") }
            state.connections.forEach { connection ->
                Text(connection.name)
                Text(connection.baseUrl.toString())
                notificationControls[connection.id]?.let { control ->
                    Text("Alert notifications")
                    Switch(control.enabled, onCheckedChange = { onNotificationToggle(connection.id, it) },
                        modifier = Modifier.semantics { contentDescription = "Notifications for ${connection.name}" })
                    when (control.activation) {
                        NotificationActivation.MUTED -> Text("Notifications: muted")
                        NotificationActivation.ACTIVE -> Text("Notifications: active")
                        NotificationActivation.PENDING -> {
                            Text("Notifications: activation pending")
                            Text("Earlier alerts stay silent until a new registration baseline is confirmed.")
                            TextButton(onClick = { onNotificationRetry(connection.id) }) { Text("Retry notifications ${connection.name}") }
                        }
                        NotificationActivation.PERMISSION_REQUIRED -> {
                            Text("Allow Varde to announce new alerts from your connections.")
                            Text("Inbox reads remain available without notification permission.")
                            TextButton(onClick = onNotificationPermission) { Text(if (control.permissionPromptAvailable) "Allow notifications" else "Notification settings") }
                        }
                    }
                    control.problem?.let { Text(it.explanation) }
                }
                state.problems[connection.id]?.let { Text(it.explanation) }
                recoveryProblems[connection.id]?.let {
                    Text("Alert recovery: ${it.explanation}")
                    TextButton(onClick = { onRecoveryRetry(connection.id) }) { Text("Retry recovery ${connection.name}") }
                }
                TextButton(onClick = { onEdit(connection.id) }, enabled = !state.saving) {
                    Text("Edit ${connection.name}")
                }
            }
            tools()
        }
    }
}
