package no.varde.app

import android.app.NotificationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import no.varde.app.inbox.*
import no.varde.app.notifications.*
import org.junit.Rule
import org.junit.Test

class NotificationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldStartTapOpensUnloadedUnavailableAlertAndRetryReadsItsSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val original = listOf(Connection.create("Synthetic Home", "https://home.example", "secret", "home"))
        val settings = object : ConnectionSettings {
            var saved = original
            override suspend fun load() = saved
            override suspend fun save(connections: List<Connection>) { saved = connections }
        }
        var unavailable = true
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
            override suspend fun read(connection: Connection, id: String): AlertDetail {
                if (unavailable) throw InboxFailure(Problem.NETWORK)
                return AlertDetail(AlertRow(id, "2026-01-01T00:00:00Z", "Cold start alert", "high", "2026-01-01T00:00:00Z", Presentation("fallback", 1)), "Unchanged original")
            }
        }
        val directory = java.io.File(context.noBackupFilesDir, "navigation-test-${java.util.UUID.randomUUID()}")
        DebugNotificationDriver.install(context, settings, backend, directory).use {
            runBlocking { DebugNotificationDriver.signal(context, "home", "alert_" + "b".repeat(32)) }
            val manager = context.getSystemService(NotificationManager::class.java)
            val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
            while (manager.activeNotifications.none { it.tag?.startsWith("varde.alert.") == true } && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(20)
            val target = manager.activeNotifications.single { it.tag?.startsWith("varde.alert.") == true }.tag.removePrefix("varde.alert.")
            ActivityScenario.launch<MainActivity>(NotificationIntents.open(context, target)).use { scenario ->
                compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Retry detail")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Synthetic Home").assertIsDisplayed()
                compose.onNodeWithText("No retained detail content is available.").assertIsDisplayed()
                unavailable = false
                compose.onNodeWithText("Retry detail").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Unchanged original")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Unchanged original").performScrollTo().assertIsDisplayed()
                scenario.recreate()
                compose.onNodeWithText("Unchanged original").performScrollTo().assertIsDisplayed()
            }
            runBlocking {
                val runtime = NotificationRuntime.get(context)
                val registration = runtime.coordinator.currentRegistration("home")!!
                runtime.settings.save(listOf(Connection.create("Other", "https://other.example", "other-secret", "home")))
                runtime.settings.save(original)
                runtime.coordinator.receive(AlertSignal("1", registration, "alert_" + "c".repeat(32)))
            }
            android.os.SystemClock.sleep(200)
            org.junit.Assert.assertFalse(manager.activeNotifications.any { it.tag?.startsWith("varde.alert.") == true })
        }
        directory.deleteRecursively()
    }
}
