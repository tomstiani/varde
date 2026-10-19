package no.varde.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.varde.app.inbox.RichComponent
import no.varde.app.inbox.RichMetric
import no.varde.app.inbox.RichText

@Composable
fun RichContent(components: List<RichComponent>) {
    components.forEach { component ->
        when (component) {
            is RichMetric -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(component.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(listOfNotNull(component.value, component.unit).joinToString(" "), style = MaterialTheme.typography.headlineMedium)
                    if (component.unit == null) Text("Unit: unknown")
                    Text("Source: ${component.source ?: "unknown"}")
                    Text("Resource: ${component.resource ?: "unknown"}")
                    Text("Collector: ${component.collector ?: "unknown"}")
                    Text("Measurement time: ${component.measuredAt ?: "unknown"}")
                }
            }
            is RichText -> Text(component.text)
        }
    }
}
