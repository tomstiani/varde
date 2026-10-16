package no.varde.app

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun eventTime(original: String): String = try {
    OffsetDateTime.parse(original).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
} catch (_: Exception) {
    // RFC 3339 leap seconds and sub-nanosecond spelling remain readable verbatim.
    original
}
