package no.varde.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
fun ConnectionScreen(state: InboxState, onBack: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(state.connection?.name.orEmpty()) }
    var url by remember { mutableStateOf(state.connection?.baseUrl?.toString().orEmpty()) }
    // Never save credential entry in instance state or navigation.
    var secret by remember { mutableStateOf("") }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack, enabled = !state.saving) { Text("Back to inbox") }
            Text("Connection settings")
            Text("Use an installation credential issued by your backend's local operator command.")
            OutlinedTextField(name, { name = it }, label = { Text("Friendly name") }, singleLine = true, enabled = !state.saving)
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS URL") }, singleLine = true, enabled = !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            OutlinedTextField(secret, { secret = it }, label = { Text("Installation credential") }, singleLine = true, enabled = !state.saving,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            state.settingsProblem?.let { Text(it.explanation) }
            Text("One connection is supported in this increment. Saving replaces the current connection and reloads its content. Re-enter its credential to save.")
            Button(onClick = {
                onSave(name, url, secret)
                secret = ""
            }, enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Save connection") }
        }
    }
}
