package no.varde.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.Connection
import no.varde.app.inbox.Problem

@Composable
fun ConnectionFailureCard(connection: Connection, problem: Problem, busy: Boolean, onRetry: (String) -> Unit, onEdit: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${connection.name}: ${problem.explanation}")
            Text("History for this connection is paused. Retry refreshes all connections from the newest results.")
            TextButton(onClick = { onRetry(connection.id) }, enabled = !busy) { Text("Retry ${connection.name}") }
            TextButton(onClick = { onEdit(connection.id) }, enabled = !busy) { Text("Edit ${connection.name}") }
        }
    }
}
