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
import no.varde.app.inbox.InboxState

@Composable
fun InboxScreen(state: InboxState, onRefresh: () -> Unit, onSettings: () -> Unit, onAlert: (String) -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Varde inbox", Modifier.padding(vertical = 16.dp))
                TextButton(onClick = onSettings, enabled = state.initialized) { Text("Settings") }
            }
            when {
                !state.initialized -> CircularProgressIndicator()
                state.connection == null -> {
                    state.settingsProblem?.let { Text(it.explanation) }
                    Text("Add a connection to read alerts from your homelab.")
                    Button(onClick = onSettings) { Text("Add connection") }
                }
                else -> {
                    Button(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
                    if (state.loading) CircularProgressIndicator()
                    state.problem?.let {
                        Text("${state.connection.name}: ${it.explanation}")
                        TextButton(onClick = onSettings) { Text("Connection settings") }
                    }
                    if (state.stale) Text("Stale results — this connection could not be refreshed.")
                    if (!state.loading && state.problem == null && state.items.isEmpty()) Text("No alerts yet.")
                    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.items, key = { it.id }) { row ->
                            Card(Modifier.fillMaxWidth().clickable { onAlert(row.id) }) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.title)
                                    Text(state.connection.name)
                                    Text("Priority: ${row.priority}")
                                    Text("Event: ${eventTime(row.observedAt)}")
                                }
                            }
                        }
                        if (state.items.isNotEmpty()) item {
                            Text("Showing the newest ${state.items.size} alerts. Older browsing comes in a later increment.")
                        }
                    }
                }
            }
        }
    }
}
