package no.varde.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import no.varde.app.inbox.*
import no.varde.app.notifications.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationControlsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appMuteCancelsRealOutputAndReenableUsesANewRegistration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val home = Connection.create("Synthetic Home", "https://home.example", "PRIVATE_credential", "home")
        val settings = object : ConnectionSettings {
            override suspend fun load() = listOf(home)
            override suspend fun save(connections: List<Connection>) = Unit
        }
        val backend = RegistrationBackend()
        val directory = File(context.noBackupFilesDir, "notification-controls-${UUID.randomUUID()}")
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            DebugNotificationDriver.install(context, settings, backend, directory,
                tokenSource = PushTokenSource { PushToken("fcm", "synthetic-private-token") }).use {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                    compose.onNodeWithText("Settings").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Notifications: active")).fetchSemanticsNodes().isNotEmpty() }
                    val coordinator = NotificationRuntime.get(context).coordinator
                    val previous = runBlocking { requireNotNull(coordinator.currentRegistration("home")) }
                    runBlocking { coordinator.receive(AlertSignal("1", previous, "alert_" + "a".repeat(32))) }
                    compose.waitUntil(5_000) { manager.activeNotifications.any { it.tag?.startsWith("varde.alert.") == true } }
                    val target = manager.activeNotifications.first { it.tag?.startsWith("varde.alert.") == true }.tag.removePrefix("varde.alert.")
                    compose.onNodeWithContentDescription("Notifications for Synthetic Home").performClick()
                    compose.waitUntil(5_000) { manager.activeNotifications.none { it.tag?.startsWith("varde.alert.") == true } }
                    compose.onNodeWithContentDescription("Notifications for Synthetic Home").assertIsOff()
                    assertNull(runBlocking { coordinator.resolveTap(target) })
                    compose.onNodeWithContentDescription("Notifications for Synthetic Home").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Notifications: active")).fetchSemanticsNodes().isNotEmpty() }
                    assertNotEquals(previous, runBlocking { coordinator.currentRegistration("home") })
                    compose.onNodeWithText("PRIVATE_credential").assertDoesNotExist()
                }
            }
        } finally {
            manager.activeNotifications.filter { it.tag?.startsWith("varde.alert.") == true }.forEach { manager.cancel(it.tag, it.id) }
            directory.deleteRecursively()
        }
    }

    @Test fun sourceToggleAndPermissionSettingsAreExplicitWithoutExposingSecrets() {
        val home = Connection.create("Synthetic Home", "https://home.example", "PRIVATE_credential", "home")
        val control = mutableStateOf(NotificationControl(true, NotificationActivation.PENDING))
        var settingsActions = 0
        compose.setContent {
            MaterialTheme {
                ConnectionsScreen(InboxState(connections = listOf(home)), {}, {}, {},
                    notificationControls = mapOf("home" to control.value),
                    onNotificationToggle = { _, enabled -> control.value = NotificationControl(enabled, if (enabled) NotificationActivation.PENDING else NotificationActivation.MUTED) },
                    onNotificationPermission = { settingsActions++ })
            }
        }
        compose.onNodeWithText("Notifications: activation pending").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notifications for Synthetic Home").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Notifications: muted").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notifications for Synthetic Home").performClick().assertIsOn()
        compose.runOnIdle { control.value = NotificationControl(true, NotificationActivation.PERMISSION_REQUIRED) }
        compose.onNodeWithText("Inbox reads remain available without notification permission.").assertIsDisplayed()
        assertEquals(0, settingsActions)
        compose.onNodeWithText("Allow notifications").performClick()
        assertEquals(1, settingsActions)
        compose.runOnIdle { control.value = control.value.copy(permissionPromptAvailable = false) }
        compose.onNodeWithText("Notification settings").assertIsDisplayed().performClick()
        assertEquals(2, settingsActions)
        compose.onNodeWithText("PRIVATE_credential").assertDoesNotExist()
    }

    private class RegistrationBackend : Backend {
        private var current = PushRegistration(null, null, false, null, 0)
        override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
        override suspend fun read(connection: Connection, id: String): AlertDetail = throw InboxFailure(Problem.NETWORK)
        override suspend fun checkpoint(connection: Connection) = "silent-muted"
        override suspend fun changes(connection: Connection, cursor: String) = ChangePage(emptyList(), cursor, false)
        override suspend fun pushRegistration(connection: Connection) = current
        override suspend fun putPush(connection: Connection, mutation: PushMutation, destination: PushDestination): PushRegistration {
            if (mutation.version != current.version) throw InboxFailure(Problem.REGISTRATION_CHANGED)
            current = PushRegistration(destination.registrationId, destination.provider, true, "baseline", mutation.version + 1)
            return current
        }
        override suspend fun deletePush(connection: Connection, mutation: PushMutation): PushRegistration {
            if (mutation.version != current.version) throw InboxFailure(Problem.REGISTRATION_CHANGED)
            current = PushRegistration(null, null, false, null, mutation.version + 1)
            return current
        }
    }
}
