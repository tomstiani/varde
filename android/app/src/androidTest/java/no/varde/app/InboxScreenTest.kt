package no.varde.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import no.varde.app.inbox.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun stop() { scope.cancel() }

    @Test fun sourceLabelDetailsAndExplicitRefresh() {
        val backend = ScreenBackend()
        val settings = ScreenSettings(listOf(Connection.create("My lab", "https://example.com", "token")))
        val inbox = Inbox(scope, settings, backend)
        compose.setContent { VardeApp(inbox) }
        waitFor("Disk pressure")
        compose.onNodeWithText("My lab").assertIsDisplayed()
        compose.onNodeWithText("Priority: high").assertIsDisplayed()
        compose.onNodeWithText("Disk pressure").performClick()
        waitFor("Complete original summary")
        compose.onNodeWithText("My lab").assertIsDisplayed()
        compose.onNodeWithText("Back to inbox").performClick()
        compose.runOnIdle { assertEquals(1, backend.calls) }
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil { backend.calls == 2 }
    }

    @Test fun emptySetupSavesOneConnectionWithoutShowingSecret() {
        val settings = ScreenSettings(emptyList())
        val inbox = Inbox(scope, settings, ScreenBackend())
        compose.setContent { VardeApp(inbox) }
        compose.onNodeWithText("Add connection").performClick()
        compose.onNodeWithText("Friendly name").performTextInput("My lab")
        compose.onNodeWithText("HTTPS URL").performTextInput("https://example.com")
        compose.onNodeWithText("Installation credential").performTextInput("SENTINEL-token")
        compose.onNodeWithText("Save connection").performScrollTo().performClick()
        waitFor("Disk pressure")
        compose.runOnIdle { assertEquals("My lab", settings.connections.single().name) }
    }

    @Test fun emptyOriginalSummaryIsNotReplacedWithInventedContent() {
        val backend = ScreenBackend(summary = "")
        val inbox = Inbox(scope, ScreenSettings(listOf(Connection.create("My lab", "https://example.com", "token"))), backend)
        compose.setContent { VardeApp(inbox) }
        waitFor("Disk pressure")
        compose.onNodeWithText("Disk pressure").performClick()
        waitFor("Summary")
        compose.onNodeWithText("Summary").assertIsDisplayed()
        compose.onNodeWithText("No summary supplied.").assertDoesNotExist()
    }

    @Test fun failedInitialLoadIsNotAnEmptyInbox() {
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage = throw InboxFailure(Problem.NETWORK)
            override suspend fun read(connection: Connection, id: String): AlertDetail = throw InboxFailure(Problem.NETWORK)
        }
        val inbox = Inbox(scope, ScreenSettings(listOf(Connection.create("My lab", "https://example.com", "token"))), backend)
        compose.setContent { VardeApp(inbox) }
        waitFor("My lab: ${Problem.NETWORK.explanation}")
        compose.onNodeWithText("No alerts yet.").assertDoesNotExist()
        compose.onNodeWithText("Connection settings").assertIsDisplayed()
    }

    @Test fun combinedSourcesCanBeRenamedAndRemovedWithoutRetypingSecrets() {
        val home = Connection.create("Home", "https://home.example.com", "home-token")
        val work = Connection.create("Work", "https://work.example.com", "work-token")
        val settings = ScreenSettings(listOf(home, work))
        val backend = ScreenBackend(sourceTitles = true)
        val inbox = Inbox(scope, settings, backend)
        compose.setContent { VardeApp(inbox) }
        waitFor("Work alert")
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Work alert").performClick()
        waitFor("Work original")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Edit Work").performClick()
        compose.onNodeWithText("Friendly name").performTextReplacement("Renamed")
        compose.onNodeWithText("work-token").assertDoesNotExist()
        compose.onNodeWithText("Save connection").performScrollTo().performClick()
        waitFor("Edit Renamed")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Renamed").assertIsDisplayed()
        compose.onNodeWithText("Work original").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, backend.calls) }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Edit Renamed").performClick()
        compose.onNodeWithText("Remove connection").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performClick()
        waitFor("Connections")
        compose.onNodeWithText("Edit Renamed").assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Reopen this alert from the inbox.").assertIsDisplayed()
        compose.onNodeWithText("Back to inbox").performClick()
        compose.onNodeWithText("Home alert").assertIsDisplayed()
        compose.onNodeWithText("Work alert").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(home.id), settings.connections.map { it.id }) }
    }

    @Test fun validationErrorsBelongOnlyToTheConnectionBeingEdited() {
        val home = Connection.create("Home", "https://home.example.com", "token")
        val inbox = Inbox(scope, ScreenSettings(listOf(home)), ScreenBackend())
        compose.setContent { VardeApp(inbox) }
        waitFor("Disk pressure")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Add connection").performClick()
        compose.onNodeWithText("Friendly name").performTextInput("Home")
        compose.onNodeWithText("HTTPS URL").performTextInput("https://other.example.com")
        compose.onNodeWithText("Installation credential").performTextInput("token")
        compose.onNodeWithText("Save connection").performScrollTo().performClick()
        waitFor(Problem.DUPLICATE_NAME.explanation)
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Edit Home").performClick()
        compose.onNodeWithText(Problem.DUPLICATE_NAME.explanation).assertDoesNotExist()
    }

    @Test fun manyUnavailableConnectionsDoNotPushHealthyAlertsOffScreen() {
        val connections = (1..12).map { Connection.create("Offline $it", "https://offline$it.example.com", "token") } +
            Connection.create("Healthy", "https://healthy.example.com", "token")
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
                if (connection.name != "Healthy") throw InboxFailure(Problem.NETWORK)
                return HistoryPage(listOf(AlertRow("one", "2026-01-01T12:00:00Z", "Healthy alert", "high", "2026-01-01T12:00:00Z", Presentation("fallback", 1))))
            }
            override suspend fun read(connection: Connection, id: String) = error("No detail requested")
        }
        val inbox = Inbox(scope, ScreenSettings(connections), backend)
        compose.setContent { VardeApp(inbox) }
        compose.waitUntil { inbox.state.value.initialized && !inbox.state.value.loading }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Healthy alert"))
        compose.onNodeWithText("Healthy alert").assertIsDisplayed()
    }

    @Test fun finishingSaveAfterBackDoesNotPopAnUnrelatedScreen() {
        val committed = CompletableDeferred<Unit>()
        val settings = object : ConnectionSettings {
            override suspend fun load() = emptyList<Connection>()
            override suspend fun save(connections: List<Connection>) { committed.await() }
        }
        val inbox = Inbox(scope, settings, ScreenBackend())
        compose.setContent { VardeApp(inbox) }
        compose.onNodeWithText("Add connection").performClick()
        compose.onNodeWithText("Friendly name").performTextInput("My lab")
        compose.onNodeWithText("HTTPS URL").performTextInput("https://example.com")
        compose.onNodeWithText("Installation credential").performTextInput("token")
        compose.onNodeWithText("Save connection").performScrollTo().performClick()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() as ComponentActivity
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("Settings").performClick()
        committed.complete(Unit)
        waitFor("Edit My lab")
        compose.onNodeWithText("Connections").assertIsDisplayed()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private class ScreenSettings(var connections: List<Connection>) : ConnectionSettings {
        override suspend fun load() = connections
        override suspend fun save(connections: List<Connection>) { this.connections = connections }
    }
    private class ScreenBackend(val summary: String = "Complete original summary", val sourceTitles: Boolean = false) : Backend {
        @Volatile var calls = 0
        private val row = AlertRow("one", "2026-01-01T12:00:00Z", "Disk pressure", "high", "2026-01-01T12:00:00Z", Presentation("fallback", 1))
        override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
            calls++
            return HistoryPage(listOf(if (sourceTitles) row.copy(title = "${connection.name} alert") else row))
        }
        override suspend fun read(connection: Connection, id: String) = AlertDetail(row, if (sourceTitles) "${connection.name} original" else summary)
    }
}
