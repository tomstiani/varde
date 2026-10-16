package no.varde.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.InboxState

@Composable
fun DetailScreen(state: InboxState, onBack: () -> Unit, onRetry: () -> Unit, onSettings: () -> Unit) {
    val detail = state.detail
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back to inbox") }
            TextButton(onClick = onSettings) { Text("Settings") }
            detail?.key?.connectionId?.let(state::connection)?.let { Text(it.name) }
            if (detail == null) Text("Reopen this alert from the inbox.")
            if (detail?.loading == true) CircularProgressIndicator()
            detail?.problem?.let {
                Text(it.explanation)
                Button(onClick = onRetry) { Text("Retry detail") }
                TextButton(onClick = onSettings) { Text("Connection settings") }
            }
            if (detail?.stale == true) Text("Stale content — the alert could not be refreshed.")
            detail?.alert?.let { alert ->
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(alert.row.title)
                        Text("Priority: ${alert.row.priority}")
                        Text("Event: ${eventTime(alert.row.observedAt)}")
                        Text("Summary")
                        Text(alert.summary)
                    }
                }
            }
        }
    }
}
