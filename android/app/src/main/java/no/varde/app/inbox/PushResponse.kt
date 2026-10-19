package no.varde.app.inbox

import okhttp3.Response
import org.json.JSONObject

internal fun parsePushRegistration(json: JSONObject, response: Response): PushRegistration {
    val tags = response.headers.values("ETag")
    require(tags.size == 1 && tags.single().matches(Regex("\"(0|[1-9][0-9]*)\"")))
    val version = requireNotNull(tags.single().drop(1).dropLast(1).toLongOrNull())
    require(json.keys().asSequence().toSet() == setOf("registrationId", "provider", "enabled", "baselineCursor"))
    val enabled = json.get("enabled")
    require(enabled is Boolean)
    fun nullableText(name: String): String? = if (json.isNull(name)) null else json.get(name).let {
        require(it is String)
        it
    }
    val id = nullableText("registrationId")
    val provider = nullableText("provider")
    val baseline = nullableText("baselineCursor")
    if (enabled) {
        require(id != null && id.matches(Regex("[0-9a-f]{32}")) && provider == "fcm")
        require(baseline != null && baseline.matches(Regex("[A-Za-z0-9_.-]{1,2048}")))
    } else require(id == null && provider == null && baseline == null)
    return PushRegistration(id, provider, enabled, baseline, version)
}

internal fun registrationError(response: Response): Problem = try {
    val source = response.body?.source()
    if (source == null || source.request(4097)) Problem.RESPONSE
    else when {
        response.code == 409 && JSONObject(source.readUtf8()).opt("error") == "idempotency_conflict" -> Problem.IDEMPOTENCY_CONFLICT
        response.code == 412 && JSONObject(source.readUtf8()).opt("error") == "registration_changed" -> Problem.REGISTRATION_CHANGED
        else -> Problem.RESPONSE
    }
} catch (_: Exception) { Problem.RESPONSE }
