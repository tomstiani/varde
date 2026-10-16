package no.varde.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val settings = ScreenSettings(Connection.create("My lab", "https://example.com", "token"))
        val inbox = Inbox(scope, settings, backend)
        compose.setContent { VardeApp(inbox) }
        compose.waitUntil { compose.onAllNodesWithText("Disk pressure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("My lab").assertIsDisplayed()
        compose.onNodeWithText("Priority: high").assertIsDisplayed()
        compose.onNodeWithText("Disk pressure").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Complete original summary").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("My lab").assertIsDisplayed()
        compose.onNodeWithText("Back to inbox").performClick()
        compose.runOnIdle { assertEquals(1, backend.calls) }
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil { backend.calls == 2 }
    }

    @Test fun emptySetupSavesOneConnectionWithoutShowingSecret() {
        val settings = ScreenSettings(null)
        val inbox = Inbox(scope, settings, ScreenBackend())
        compose.setContent { VardeApp(inbox) }
        compose.onNodeWithText("Add connection").performClick()
        compose.onNodeWithText("Friendly name").performTextInput("My lab")
        compose.onNodeWithText("HTTPS URL").performTextInput("https://example.com")
        compose.onNodeWithText("Installation credential").performTextInput("SENTINEL-token")
        compose.onNodeWithText("Save connection").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Disk pressure").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals("My lab", settings.connection?.name) }
    }

    private class ScreenSettings(var connection: Connection?) : ConnectionSettings {
        override suspend fun load() = connection
        override suspend fun save(connection: Connection) { this.connection = connection }
    }
    private class ScreenBackend : Backend {
        @Volatile var calls = 0
        private val row = AlertRow("one", "2026-01-01T12:00:00Z", "Disk pressure", "high", "2026-01-01T12:00:00Z", Presentation("fallback", 1))
        override suspend fun history(connection: Connection): List<AlertRow> { calls++; return listOf(row) }
        override suspend fun read(connection: Connection, id: String) = AlertDetail(row, "Complete original summary")
    }
}
