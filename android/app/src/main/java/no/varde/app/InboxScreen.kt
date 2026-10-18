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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.AlertKey
import no.varde.app.inbox.InboxState
import no.varde.app.inbox.Problem

@Composable
fun InboxScreen(state: InboxState, onRefresh: () -> Unit, onLoadOlder: () -> Unit, onSettings: () -> Unit, onAdd: () -> Unit, onAlert: (AlertKey) -> Unit, onRetryConnection: (String) -> Unit, onEditConnection: (String) -> Unit, recoveryProblems: Map<String, Problem> = emptyMap()) {
    val listState = rememberLazyListState()
    var lastRefresh by rememberSaveable { mutableIntStateOf(state.refreshRevision) }
    LaunchedEffect(state.refreshRevision) {
        if (lastRefresh != state.refreshRevision) {
            listState.scrollToItem(0)
            lastRefresh = state.refreshRevision
        }
    }
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
                    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (recoveryProblems.isNotEmpty()) item(key = "recovery-incomplete") {
                            Column {
                                Text("Alert recovery is incomplete for ${state.connections.filter { it.id in recoveryProblems }.joinToString { it.name }}.")
                                TextButton(onClick = onSettings) { Text("Alert recovery settings") }
                            }
                        }
                        if (state.problems.isNotEmpty()) item(key = "incomplete") {
                            Column {
                                Text(when {
                                    state.loading -> "Refreshing connections. Previously displayed content remains visible."
                                    state.problems.size < state.connections.size -> "Some connections are unavailable."
                                    state.items.isEmpty() -> "No connections are available."
                                    else -> "All connections are unavailable. Showing stale results."
                                })
                                TextButton(onClick = onSettings) { Text("Connection settings") }
                            }
                        }
                        items(state.problems.entries.toList(), key = { "problem/${it.key}" }) { (id, problem) ->
                            state.connection(id)?.let { connection ->
                                ConnectionFailureCard(connection, problem, state.loading || state.saving, onRetryConnection, onEditConnection)
                            }
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
                        item(key = "pagination") {
                            when {
                                state.loadingOlder -> CircularProgressIndicator()
                                state.hasMore -> Button(onClick = onLoadOlder, enabled = !state.loading) { Text("Load older") }
                                !state.loading && state.historyComplete && state.items.isNotEmpty() -> Text("All alerts in this snapshot are loaded.")
                                !state.loading && state.problems.isNotEmpty() -> Text("History is incomplete. Refresh to retry.")
                            }
                        }
                    }
                }
            }
        }
    }
}
