package no.varde.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import no.varde.app.inbox.*
import no.varde.app.notifications.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class NotificationPermissionDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun explicitDenialDoesNotPromptAgainAndGrantInitializesSilently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        // Run this class alone after a fresh managed install; revocation can kill instrumentation.
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED)
        val home = Connection.create("Synthetic Home", "https://home.example", "synthetic-secret", "home")
        val settings = object : ConnectionSettings {
            override suspend fun load() = listOf(home)
            override suspend fun save(connections: List<Connection>) = Unit
        }
        val registrations = AtomicInteger()
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
            override suspend fun read(connection: Connection, id: String): AlertDetail = error("Unexpected body read")
            override suspend fun checkpoint(connection: Connection) = "silent-denied"
            override suspend fun changes(connection: Connection, cursor: String) = ChangePage(emptyList(), cursor, false)
            override suspend fun pushRegistration(connection: Connection) = PushRegistration(null, null, false, null, 0)
            override suspend fun putPush(connection: Connection, mutation: PushMutation, destination: PushDestination): PushRegistration {
                registrations.incrementAndGet()
                return PushRegistration(destination.registrationId, destination.provider, true, "fresh-after-grant", 1)
            }
        }
        val directory = File(context.noBackupFilesDir, "permission-dialog-${UUID.randomUUID()}")
        try {
            DebugNotificationDriver.install(context, settings, backend, directory,
                tokenSource = PushTokenSource { PushToken("fcm", "synthetic-token") }).use {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                    compose.onNodeWithText("Settings").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Allow notifications")).fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(0, registrations.get())
                    compose.onNodeWithText("Allow notifications").performClick()
                    denySystemPermission()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Notification settings")).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Inbox reads remain available without notification permission.").assertIsDisplayed()
                    assertEquals(0, registrations.get())
                    scenario.recreate()
                    if (compose.onAllNodes(hasText("Settings")).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Settings").performClick()
                    compose.onNodeWithText("Notification settings").assertIsDisplayed()
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Notifications: active")).fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(1, registrations.get())
                    assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun denySystemPermission() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val flags = info.flags
        info.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = automation.rootInActiveWindow
                for (controller in listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")) {
                    val denied = root?.findAccessibilityNodeInfosByViewId("$controller:id/permission_deny_button").orEmpty()
                    if (denied.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) return
                }
                SystemClock.sleep(50)
            }
            fail("The Android notification-permission denial button was not shown")
        } finally {
            info.flags = flags
            automation.serviceInfo = info
        }
    }
}
