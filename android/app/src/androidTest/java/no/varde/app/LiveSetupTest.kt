package no.varde.app

import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import no.varde.app.inbox.Inbox
import no.varde.app.inbox.Connection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.HttpURLConnection

@RunWith(AndroidJUnit4::class)
class LiveSetupTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun probeConnection(baseUrl: String): JSONObject {
        val result = JSONObject()
        var connection: HttpURLConnection? = null
        try {
            val target = URI.create(baseUrl).resolve("v1/alerts")
            result.put("dnsResolved", InetAddress.getAllByName(target.host).isNotEmpty())
            connection = target.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            result.put("httpStatus", connection.responseCode)
        } catch (failure: Exception) {
            result.put("failureType", failure.javaClass.simpleName)
            result.put("causeType", failure.cause?.javaClass?.simpleName.orEmpty())
        } finally {
            connection?.disconnect()
        }
        return result
    }

    @Test fun configureAndBrowseOperatorApprovedLocalAcceptance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("varde.acceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = File(context.noBackupFilesDir, "live-acceptance.json")
        val evidence = File(context.noBackupFilesDir, "live-result.json")
        var stage = "read-private-profile"
        var inbox: Inbox? = null
        try {
            val configuration = JSONObject(profile.readText())
            val connections = configuration.getJSONArray("connections")
            require(connections.length() == 2)
            val transport = configuration.getString("transport")
            require(transport in setOf("usb-loopback", "trusted-https"))
            val previous = configuration.optJSONArray("previousConnections") ?: org.json.JSONArray()
            fun url(entry: JSONObject) = Connection.create(entry.getString("name"), entry.getString("url"), entry.getJSONObject("installation").getString("secret")).baseUrl.toString()
            require((0 until connections.length()).all { index ->
                val target = URI.create(url(connections.getJSONObject(index)))
                if (transport == "usb-loopback") target.scheme == "http" && target.host == "127.0.0.1" else target.scheme == "https"
            })
            stage = "unlock-phone"
            require(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity {
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    inbox = ViewModelProvider(it)[InboxViewModel::class.java].inbox
                }
                stage = "phone-connection-preflight"
                val probes = (0 until connections.length()).map { index -> probeConnection(url(connections.getJSONObject(index))) }
                File(context.noBackupFilesDir, "live-network.json").writeText(org.json.JSONArray(probes).toString())
                require(probes.all { it.optInt("httpStatus") == 401 })
                compose.waitUntil(15_000) { inbox!!.state.value.initialized }
                val existing = inbox!!.state.value.connections
                require(existing.all { current ->
                    (0 until connections.length()).any { index ->
                        val expected = connections.getJSONObject(index)
                        val previouslyApproved = (0 until previous.length()).any { oldIndex ->
                            val old = previous.getJSONObject(oldIndex)
                            current.name == old.getString("name") && current.baseUrl.toString() == old.getString("url")
                        }
                        current.name == expected.getString("name") && (current.baseUrl.toString() == url(expected) || previouslyApproved)
                    }
                })
                stage = "configure-connections"
                for (index in 0 until connections.length()) {
                    val connection = connections.getJSONObject(index)
                    val name = connection.getString("name")
                    val target = url(connection)
                    val secret = connection.getJSONObject("installation").getString("secret")
                    val current = inbox!!.state.value.connections.find { it.name == name }
                    if (current != null && current.baseUrl.toString() == target && current.secret == secret) continue
                    compose.onNodeWithText("Settings").performClick()
                    if (current == null) {
                        compose.onNodeWithText("Add connection").performClick()
                        compose.onNodeWithText("Friendly name").performTextInput(name)
                    } else compose.onNodeWithText("Edit $name").performClick()
                    compose.onNodeWithText("HTTPS URL").performTextReplacement(target)
                    compose.onNodeWithText("Installation credential").performTextInput(secret)
                    compose.onNodeWithText("Save connection").performScrollTo().performClick()
                    compose.waitUntil(15_000) { !inbox!!.state.value.saving && inbox!!.state.value.connections.any { it.name == name && it.baseUrl.toString() == target } }
                    compose.onNodeWithText("Back").performClick()
                }
                stage = "authenticated-first-page"
                compose.waitUntil(45_000) { !inbox!!.state.value.loading && inbox!!.state.value.connections.size == 2 }
                require(inbox!!.state.value.problems.isEmpty())
                assertEquals(50, inbox!!.state.value.items.size)
                compose.onAllNodesWithText("Mac A").onFirst().assertIsDisplayed()
                compose.onAllNodesWithText("Mac B").onFirst().assertIsDisplayed()
                stage = "read-unchanged-original"
                compose.onNodeWithText(configuration.getString("expectedTitle")).performClick()
                compose.waitUntil(15_000) { inbox!!.state.value.detail?.loading == false }
                assertEquals(configuration.getString("expectedSummary"), inbox!!.state.value.detail?.alert?.summary)
                compose.onNodeWithText("Back to inbox").performClick()
                stage = "browse-bounded-history"
                repeat(10) {
                    if (!inbox!!.state.value.hasMore) return@repeat
                    val previousSize = inbox!!.state.value.items.size
                    compose.onNode(hasScrollAction()).performScrollToNode(hasText("Load older"))
                    compose.onNodeWithText("Load older").performClick()
                    compose.waitUntil(30_000) { !inbox!!.state.value.loadingOlder && inbox!!.state.value.items.size > previousSize }
                }
                assertEquals(configuration.getInt("expectedAlertCount"), inbox!!.state.value.items.size)
                assertTrue(inbox!!.state.value.historyComplete)
                assertTrue(inbox!!.state.value.problems.isEmpty())
                assertEquals(inbox!!.state.value.items.size, inbox!!.state.value.items.map { it.key }.distinct().size)
                val counts = inbox!!.state.value.items.groupingBy { item -> inbox!!.state.value.connection(item.key.connectionId)!!.name }.eachCount()
                evidence.writeText(JSONObject().put("passed", true).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                    .put("rows", inbox!!.state.value.items.size).put("sources", JSONObject(counts)).put("transport", if (transport == "usb-loopback") "usb-loopback-http" else "platform-trusted-https").toString())
            }
        } catch (_: Throwable) {
            evidence.writeText(JSONObject().put("passed", false).put("stage", stage)
                .put("categories", inbox?.state?.value?.problems?.values?.map { it.name }.orEmpty()).toString())
            // UI assertion dumps may include text-field input; never attach their original exception.
            throw AssertionError("Live acceptance setup failed; read the redacted app-private result.")
        } finally {
            profile.delete()
        }
    }
}
