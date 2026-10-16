package no.varde.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import no.varde.app.inbox.Inbox

@Composable
fun VardeApp(inbox: Inbox) {
    val state by inbox.state.collectAsStateWithLifecycle()
    val navigation = rememberNavController()
    MaterialTheme {
        Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            NavHost(navigation, startDestination = "inbox") {
                composable("inbox") {
                    InboxScreen(state, inbox::refresh,
                        onSettings = { navigation.navigate("settings") },
                        onAlert = { id ->
                            inbox.open(id)
                            navigation.navigate("detail")
                        })
                }
                composable("settings") {
                    ConnectionScreen(state,
                        onBack = { navigation.popBackStack() },
                        onSave = { name, url, secret ->
                            inbox.save(name, url, secret) { navigation.popBackStack() }
                        })
                }
                composable("detail") {
                    DetailScreen(state,
                        onBack = { inbox.closeDetail(); navigation.popBackStack() },
                        onRetry = { state.detail?.id?.let(inbox::open) },
                        onSettings = { navigation.navigate("settings") })
                }
            }
        }
    }
}
