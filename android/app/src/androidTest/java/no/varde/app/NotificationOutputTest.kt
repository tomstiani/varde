package no.varde.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import no.varde.app.notifications.*
import no.varde.app.inbox.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationOutputTest {
    @Test fun coordinatorEnrichesAnImmediateReadThroughRealAndroidOutput() = kotlinx.coroutines.runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val connection = Connection.create("Home", "https://home.example", "secret", "home")
        val settings = object : ConnectionSettings {
            override suspend fun load() = listOf(connection)
            override suspend fun save(connections: List<Connection>) = Unit
        }
        val backend = object : Backend {
            override suspend fun history(connection: Connection, cursor: String?) = HistoryPage(emptyList())
            override suspend fun read(connection: Connection, id: String) = AlertDetail(
                AlertRow(id, "2026-01-01T00:00:00Z", "Immediate title", "normal", "2026-01-01T00:00:00Z", Presentation("fallback", 1)), "Immediate summary")
        }
        val directory = java.io.File(context.noBackupFilesDir, "notification-output-test-${java.util.UUID.randomUUID()}")
        val output = AndroidNotificationOutput(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val coordinator = NotificationCoordinator(settings, backend, directory, output)
            coordinator.activate("home", "d".repeat(32))
            coordinator.receive(AlertSignal("1", "d".repeat(32), "alert_" + "e".repeat(32)))
            val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
            while (manager.activeNotifications.none { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Immediate title" } &&
                android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(20)
            assertTrue(manager.activeNotifications.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Immediate title" })
        } finally {
            manager.activeNotifications.filter { it.tag?.startsWith("varde.alert.") == true }.forEach { manager.cancel(it.tag, it.id) }
            directory.deleteRecursively()
        }
    }

    @Test fun privateContentUpdatesOneNotificationWithAnImmutableIsolatedTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val output = AndroidNotificationOutput(context)
        val first = "a".repeat(32)
        val second = "b".repeat(32)
        try {
            output.show(AlertNotice(first, "Home"), true)
            val detail = AlertDetail(AlertRow("alert_" + "c".repeat(32), "2026-01-01T00:00:00Z", "Private title", "high", "2026-01-01T00:00:00Z", Presentation("fallback", 1)), "Private summary")
            output.show(AlertNotice(first, "Home", detail), false)
            output.show(AlertNotice(second, "Cabin"), true)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (manager.activeNotifications.count { it.tag?.startsWith("varde.alert.") == true } != 2 &&
                android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(20)
            val posted = manager.activeNotifications.filter { it.tag?.startsWith("varde.alert.") == true }
            assertEquals(2, posted.size)
            val notification = posted.single { it.tag == "varde.alert.$first" }.notification
            assertEquals("Private title", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("New alert from Home", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertFalse(notification.publicVersion.extras.toString().contains("Private"))
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.contentIntent.isImmutable)
            assertNotEquals(notification.contentIntent, posted.single { it.tag == "varde.alert.$second" }.notification.contentIntent)
            val intent = NotificationIntents.open(context, first)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertNull(intent.extras)
            assertEquals(first, NotificationIntents.openTarget(intent))
            intent.data = android.net.Uri.parse("https://attacker.example/$first")
            assertNull(NotificationIntents.openTarget(intent))
            output.cancel(first)
            val cancelledBy = android.os.SystemClock.elapsedRealtime() + 5_000
            while (output.active(first) && android.os.SystemClock.elapsedRealtime() < cancelledBy) android.os.SystemClock.sleep(20)
            assertFalse(output.active(first))
        } finally {
            output.cancel(first)
            output.cancel(second)
        }
    }
}
