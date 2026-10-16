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
    val addConnection = { inbox.beginConnectionEdit(); navigation.navigate("connection/new") }
    val editConnection = { id: String -> inbox.beginConnectionEdit(); navigation.navigate("connection/$id") }
    MaterialTheme {
        Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            NavHost(navigation, startDestination = "inbox") {
                composable("inbox") {
                    InboxScreen(state, inbox::refresh, inbox::loadOlder,
                        onSettings = { navigation.navigate("settings") },
                        onAdd = addConnection,
                        onAlert = { key ->
                            inbox.open(key)
                            navigation.navigate("detail")
                        },
                        onRetryConnection = inbox::retryConnection,
                        onEditConnection = editConnection)
                }
                composable("settings") {
                    ConnectionsScreen(state,
                        onBack = { navigation.popBackStack() },
                        onAdd = addConnection,
                        onEdit = editConnection)
                }
                composable("connection/{id}") { entry ->
                    val id = entry.arguments?.getString("id")?.takeUnless { it == "new" }
                    ConnectionScreen(state, id,
                        onBack = { navigation.popBackStack() },
                        onSave = { name, url, secret ->
                            inbox.save(id, name, url, secret) {
                                if (navigation.currentBackStackEntry?.id == entry.id) navigation.popBackStack()
                            }
                        },
                        onRemove = { id?.let {
                            inbox.remove(it) {
                                if (navigation.currentBackStackEntry?.id == entry.id) navigation.popBackStack()
                            }
                        } })
                }
                composable("detail") {
                    DetailScreen(state,
                        onBack = { inbox.closeDetail(); navigation.popBackStack() },
                        onRetry = inbox::retryDetail,
                        onSettings = { navigation.navigate("settings") },
                        onRetryConnection = inbox::retryConnection,
                        onEditConnection = editConnection)
                }
            }
        }
    }
}
