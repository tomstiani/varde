package no.varde.app.inbox

sealed interface RichComponent

data class RichMetric(
    val label: String,
    val value: String,
    val unit: String?,
    val source: String?,
    val resource: String?,
    val collector: String?,
    val measuredAt: String?,
) : RichComponent

data class RichText(val text: String) : RichComponent

internal fun decodeRichComponents(body: String): List<RichComponent> = try {
    val root = ExactJson(body).decode()
    val presentation = root["presentation"] as ExactObject
    require(presentation.keys == setOf("state", "revision"))
    if (presentation["state"] != "rich") {
        require(!root.containsKey("richView"))
        emptyList()
    } else {
        require(presentation["revision"] == ExactNumber("1"))
        val original = root["original"] as ExactObject
        require(original.keys.all { it in setOf("schemaVersion", "eventId", "title", "summary", "priority", "observedAt", "data", "dataDefaults") })
        val metrics = decodeSuppliedMeasurements(original)
        val summary = original["summary"] as String
        require(summary.codePointCount(0, summary.length) <= 8192)
        decodeSelectedComponents(root["richView"] as ExactObject, metrics, summary)
    }
} catch (_: Exception) {
    emptyList()
}

private fun decodeSelectedComponents(view: ExactObject, metrics: List<RichMetric?>, summary: String): List<RichComponent> {
    require(view.byteCount <= 8192 && view.keys == setOf("schemaVersion", "components"))
    require(view["schemaVersion"] == ExactNumber("1"))
    val components = view["components"] as List<*>
    require(components.size in 1..8)
    val seen = mutableSetOf<String>()
    return components.map { raw ->
        val component = raw as ExactObject
        when (component["type"]) {
            "metric" -> {
                require(component.keys == setOf("type", "dataIndex", "reading"))
                require(component["reading"] == "value")
                val indexToken = (component["dataIndex"] as ExactNumber).token
                require(indexToken.matches(Regex("[0-7]")))
                val index = indexToken.toInt()
                require(seen.add("d$index.metric"))
                requireNotNull(metrics.getOrNull(index))
            }
            "text" -> {
                require(component.keys == setOf("type", "field") && component["field"] == "summary")
                require(summary.isNotBlank() && metrics.any { it != null } && seen.add("summary"))
                RichText(summary)
            }
            else -> error("Unsupported component")
        }
    }
}
