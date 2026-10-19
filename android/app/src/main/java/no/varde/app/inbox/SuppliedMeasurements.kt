package no.varde.app.inbox

import java.math.BigDecimal
import java.time.LocalDateTime

internal fun decodeSuppliedMeasurements(original: ExactObject): List<RichMetric?> {
    require(original["schemaVersion"] == ExactNumber("1"))
    val defaults = if (original.containsKey("dataDefaults")) original["dataDefaults"] as ExactObject else ExactObject()
    require(defaults.keys.all { it in setOf("source", "resource", "collector") })
    defaults.values.forEach { value -> value?.let { measurementLabel(it, 128) } }
    val entries = if (original.containsKey("data")) original["data"] as List<*> else emptyList<Any?>()
    require(entries.size <= 8)
    val ids = mutableSetOf<String>()
    return entries.map { entry ->
        val fields = entry as ExactObject
        if (fields.containsKey("id")) {
            val id = fields["id"] as String
            require(id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) && ids.add(id))
        }
        decodeMeasurement(fields, defaults)
    }
}

private fun decodeMeasurement(fields: ExactObject, defaults: ExactObject): RichMetric? {
    require(fields.keys.all { it in setOf("type", "label", "id", "value", "unit", "source", "resource", "collector", "measuredAt") })
    require(fields["type"] == "measurement")
    val label = measurementLabel(fields["label"], 128)
    for (name in listOf("source", "resource", "collector")) fields[name]?.let { measurementLabel(it, 128) }
    val unit = fields["unit"]?.let { measurementLabel(it, 32) }
    val measuredAt = if (fields.containsKey("measuredAt")) typedTimestamp(fields["measuredAt"]) else null
    require(fields.containsKey("value"))
    val value = fields["value"]?.let { exactDecimal(it) } ?: return null
    fun metadata(name: String): String? = (if (fields.containsKey(name)) fields[name] else defaults[name]) as String?
    return RichMetric(
        label = label,
        value = value,
        unit = unit,
        source = metadata("source"),
        resource = metadata("resource"),
        collector = metadata("collector"),
        measuredAt = measuredAt,
    )
}

private fun measurementLabel(raw: Any?, maximum: Int): String {
    val value = raw as String
    require(value.isNotBlank() && value.codePointCount(0, value.length) <= maximum)
    require(value.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' })
    return value
}

private fun exactDecimal(raw: Any): String {
    val token = (raw as ExactNumber).token
    require(token.length <= 64)
    val parts = token.lowercase().split('e')
    if (parts.size == 2) require(parts[1].toInt() in -100..100)
    val number = BigDecimal(token)
    require(number.precision() <= 34)
    if (number.signum() != 0) require(number.precision() - number.scale() - 1 in -100..100)
    return token
}

private fun typedTimestamp(raw: Any?): String {
    val value = raw as String
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})")))
    require(!value.endsWith("-00:00"))
    val localEnd = if (value.endsWith('Z')) value.length - 1 else value.length - 6
    LocalDateTime.parse(value.substring(0, localEnd))
    if (!value.endsWith('Z')) {
        require(value.substring(value.length - 5, value.length - 3).toInt() in 0..23)
        require(value.takeLast(2).toInt() in 0..59)
    }
    return value
}
