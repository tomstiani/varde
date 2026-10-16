package no.varde.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.AlertKey
import no.varde.app.inbox.InboxState

@Composable
fun InboxScreen(state: InboxState, onRefresh: () -> Unit, onSettings: () -> Unit, onAdd: () -> Unit, onAlert: (AlertKey) -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Varde inbox", Modifier.padding(vertical = 16.dp))
                TextButton(onClick = onSettings, enabled = state.initialized) { Text("Settings") }
            }
            when {
                !state.initialized -> CircularProgressIndicator()
                state.connections.isEmpty() -> {
                    state.settingsProblem?.let { Text(it.explanation) }
                    Text("Add a connection to read alerts from your homelab.")
                    Button(onClick = onAdd) { Text("Add connection") }
                }
                else -> {
                    Button(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
                    if (state.loading) CircularProgressIndicator()
                    if (!state.loading && state.problems.isEmpty() && state.items.isEmpty()) Text("No alerts yet.")
                    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.problems.isNotEmpty()) item(key = "incomplete") {
                            Column {
                                Text("Results are incomplete. Refresh explicitly to retry unavailable connections.")
                                TextButton(onClick = onSettings) { Text("Connection settings") }
                            }
                        }
                        items(state.problems.entries.toList(), key = { "problem/${it.key}" }) { (id, problem) ->
                            Text("${state.connection(id)?.name.orEmpty()}: ${problem.explanation}")
                        }
                        items(state.items, key = { "alert/${it.key.connectionId}/${it.key.alertId}" }) { item ->
                            Card(Modifier.fillMaxWidth().clickable { onAlert(item.key) }) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.row.title)
                                    Text(state.connection(item.key.connectionId)?.name.orEmpty())
                                    if (item.key.connectionId in state.staleConnections) Text("Stale — source could not be refreshed")
                                    Text("Priority: ${item.row.priority}")
                                    Text("Event: ${eventTime(item.row.observedAt)}")
                                }
                            }
                        }
                        if (state.items.isNotEmpty()) item {
                            Text("Showing the newest loaded results. Older browsing comes in a later increment.")
                        }
                    }
                }
            }
        }
    }
}
