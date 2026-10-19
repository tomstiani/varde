package no.varde.app

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.*
import org.junit.Rule
import org.junit.Test

class RichMetricScreenTest {
    @get:Rule val compose = createComposeRule()
    private val metric = """{"type":"measurement","label":"CPU usage","value":83.00,"unit":"%","collector":"agent-a"}"""
    private val components = """[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"text","field":"summary"}]"""

    private fun show(data: String = metric, selected: String = components, narrow: Boolean = false) {
        val body = """{"id":"one","acceptedAt":"2026-10-19T12:03:00Z","original":{"schemaVersion":1,"eventId":"synthetic","title":"Original title","priority":"high","observedAt":"2026-10-19T12:00:00Z","dataDefaults":{"source":"monitor","resource":"host-a"},"data":[$data],"summary":"Full supplied context."},"presentation":{"state":"rich","revision":1},"richView":{"schemaVersion":1,"components":$selected}}"""
        val connection = Connection.create("Synthetic lab", "https://example.com", "fake-key")
        val alert = decodeAlertDetail(body, "one")
        val state = InboxState(connections = listOf(connection), detail = DetailState(AlertKey(connection.id, "one"), alert, loading = false))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (narrow) 2f else 1f)) {
                    androidx.compose.foundation.layout.Box(if (narrow) Modifier.requiredWidth(240.dp) else Modifier) {
                        DetailScreen(state, {}, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    private fun visible(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun exactValuesMetadataAndUnchangedOriginalRemainReadable() {
        show()
        compose.onNodeWithText("Original title").assertIsDisplayed()
        visible("83.00 %")
        visible("Source: monitor")
        visible("Resource: host-a")
        visible("Collector: agent-a")
        visible("Measurement time: unknown")
        visible("Summary")
    }

    @Test fun invalidWholeViewRendersOnlyOriginalText() {
        show(selected = """[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"unknown"}]""")
        compose.onNodeWithText("CPU usage").assertDoesNotExist()
        compose.onNodeWithText("83.00 %").assertDoesNotExist()
        visible("Full supplied context.")
        visible("Original title")
    }

    @Test fun unknownMetadataAndExactValuesRemainReadableInNarrowLargeTextLayout() {
        show(data = """{"type":"measurement","label":"CPU usage","value":9007199254740993,"source":null,"resource":null}""", selected = """[{"type":"metric","dataIndex":0,"reading":"value"}]""", narrow = true)
        visible("9007199254740993")
        visible("Source: unknown")
        visible("Resource: unknown")
        visible("Collector: unknown")
        visible("Unit: unknown")
        visible("Measurement time: unknown")
        visible("Summary")
    }
}
