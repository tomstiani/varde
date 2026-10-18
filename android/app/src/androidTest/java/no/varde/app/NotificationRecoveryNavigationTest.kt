package no.varde.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import no.varde.app.inbox.*
import no.varde.app.notifications.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationRecoveryNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun recoveryFailureIsConnectionSpecificAndRetryDoesNotPostAnOutageNotification() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val connection=Connection.create("Synthetic Home","https://home.example","secret","home")
        val settings=object:ConnectionSettings {
            override suspend fun load()=listOf(connection)
            override suspend fun save(connections:List<Connection>)=Unit
        }
        var unavailable=true
        val backend=object:Backend {
            override suspend fun checkpoint(connection:Connection):String { if(unavailable) throw InboxFailure(Problem.AUTHENTICATION); return "baseline" }
            override suspend fun changes(connection:Connection,cursor:String)=ChangePage(emptyList(),cursor,false)
            override suspend fun history(connection:Connection,cursor:String?)=HistoryPage(emptyList())
            override suspend fun read(connection:Connection,id:String):AlertDetail=error("Unexpected detail read")
        }
        val directory=java.io.File(context.noBackupFilesDir,"recovery-failure-${java.util.UUID.randomUUID()}")
        DebugNotificationDriver.install(context,settings,backend,directory).use {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
                compose.onNodeWithText("Settings").performClick()
                val message="Alert recovery: ${Problem.AUTHENTICATION.explanation}"
                compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(message)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(message).assertIsDisplayed()
                unavailable=false
                compose.onNodeWithText("Retry recovery Synthetic Home").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(message)).fetchSemanticsNodes().isEmpty() }
                assertEquals(0,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
            }
        }
        directory.deleteRecursively()
    }

    @Test fun openingRecoversWithoutBodyPrefetchAndCatchUpTapRefreshesTheInbox() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        if(Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val connection=Connection.create("Synthetic Home","https://home.example","secret","home")
        val settings=object:ConnectionSettings {
            override suspend fun load()=listOf(connection)
            override suspend fun save(connections:List<Connection>)=Unit
        }
        val alert="alert_"+"a".repeat(32)
        var title="Existing inbox row"
        var reads=0
        val backend=object:Backend {
            override suspend fun checkpoint(connection:Connection)="baseline"
            override suspend fun changes(connection:Connection,cursor:String)=if(cursor=="baseline") ChangePage(listOf(ChangeEntry(alert,"accepted",0)),"after",false) else ChangePage(emptyList(),cursor,false)
            override suspend fun history(connection:Connection,cursor:String?)=HistoryPage(listOf(AlertRow(alert,"2026-01-01T00:00:00Z",title,"high","2026-01-01T00:00:00Z",Presentation("fallback",1))))
            override suspend fun read(connection:Connection,id:String):AlertDetail { reads++; throw InboxFailure(Problem.NOT_FOUND) }
        }
        val directory=java.io.File(context.noBackupFilesDir,"recovery-navigation-${java.util.UUID.randomUUID()}")
        val manager=context.getSystemService(NotificationManager::class.java)
        DebugNotificationDriver.install(context,settings,backend,directory).use {
            runBlocking { NotificationRuntime.get(context).coordinator.recover("home") }
            try {
                ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                    compose.waitUntil(5_000) { manager.activeNotifications.any { it.notification.extras.getBoolean("varde.catchup") } }
                    compose.onNodeWithText("Existing inbox row").assertIsDisplayed()
                    assertEquals(0,reads)
                    compose.onNodeWithText("Settings").performClick()
                    compose.onNodeWithText("Connections").assertIsDisplayed()
                    title="Inbox refreshed by catch-up tap"
                    val target = manager.activeNotifications.single { it.notification.extras.getBoolean("varde.catchup") }.tag.removePrefix("varde.alert.")
                    // Exercise the activity's navigation entry point without creating another instrumentation task.
                    scenario.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[InboxViewModel::class.java].openNotification(NotificationIntents.open(context, target)) }
                    compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Inbox refreshed by catch-up tap")).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Inbox refreshed by catch-up tap").assertIsDisplayed()
                    assertEquals(0,reads)
                }
            } finally { manager.activeNotifications.filter { it.tag?.startsWith("varde.alert.")==true }.forEach { manager.cancel(it.tag,it.id) } }
        }
        directory.deleteRecursively()
    }
}
