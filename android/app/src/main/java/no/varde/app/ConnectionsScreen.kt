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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.InboxState

@Composable
fun ConnectionsScreen(state: InboxState, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit, tools: @Composable () -> Unit = {}) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Connections")
            Button(onClick = onAdd, enabled = !state.saving) { Text("Add connection") }
            state.connections.forEach { connection ->
                Text(connection.name)
                Text(connection.baseUrl.toString())
                state.problems[connection.id]?.let { Text(it.explanation) }
                TextButton(onClick = { onEdit(connection.id) }, enabled = !state.saving) {
                    Text("Edit ${connection.name}")
                }
            }
            tools()
        }
    }
}
