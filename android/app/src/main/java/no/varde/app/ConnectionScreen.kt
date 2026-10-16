package no.varde.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.InboxState

@Composable
fun ConnectionScreen(state: InboxState, connectionId: String?, onBack: () -> Unit, onSave: (String, String, String) -> Unit, onRemove: () -> Unit) {
    val connection = connectionId?.let(state::connection)
    var name by remember(connectionId) { mutableStateOf(connection?.name.orEmpty()) }
    var url by remember(connectionId) { mutableStateOf(connection?.baseUrl?.toString().orEmpty()) }
    // Never save credential entry in instance state or navigation.
    var secret by remember(connectionId) { mutableStateOf("") }
    var confirmRemoval by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack, enabled = !state.saving) { Text("Back") }
            Text(if (connectionId == null) "New connection" else "Edit connection")
            Text("Use an installation credential issued by your backend's local operator command.")
            OutlinedTextField(name, { name = it }, label = { Text("Friendly name") }, singleLine = true, enabled = !state.saving)
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS URL") }, singleLine = true, enabled = !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            OutlinedTextField(secret, { secret = it }, label = { Text("Installation credential") }, singleLine = true, enabled = !state.saving,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            if (connection != null) Text("Leave the credential blank to keep it. Changing the backend URL requires entering a credential explicitly.")
            state.settingsProblem?.let { Text(it.explanation) }
            Button(onClick = {
                onSave(name, url, secret)
                secret = ""
            }, enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Save connection") }
            if (connection != null) {
                TextButton(onClick = { confirmRemoval = true }, enabled = !state.saving) { Text("Remove connection") }
            }
        }
    }
    if (confirmRemoval) {
        AlertDialog(onDismissRequest = { confirmRemoval = false },
            title = { Text("Remove ${connection?.name.orEmpty()}?") },
            text = { Text("This removes local settings and displayed content, not alerts or credentials stored on the backend.") },
            confirmButton = { TextButton(onClick = { confirmRemoval = false; onRemove() }, enabled = !state.saving) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } })
    }
}
