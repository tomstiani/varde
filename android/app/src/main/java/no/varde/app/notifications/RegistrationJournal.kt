package no.varde.app.notifications

import no.varde.app.inbox.PushDestination
import org.json.JSONObject

internal fun readRegistration(json: JSONObject): RegistrationState {
    fun text(name: String): String? = if (json.isNull(name)) null else json.get(name).let { require(it is String); it }
    val version = if (json.isNull("version")) null else json.get("version").let {
        require(it is Int || it is Long)
        (it as Number).toLong().also { value -> require(value >= 0) }
    }
    require(json.get("ready") is Boolean)
    return RegistrationState(json.getBoolean("ready"), text("baseline"), version, text("key"), text("provider"), text("destination")).also {
        require(json.get("removalComplete") is Boolean)
        it.removalComplete = json.getBoolean("removalComplete")
        require(it.baseline == null || it.baseline!!.matches(Regex("[A-Za-z0-9_.-]{1,2048}")))
        require(it.key == null || it.key!!.matches(Regex("[0-9a-f]{32}")))
        require(!it.ready || (it.baseline != null && it.version != null))
        require((it.provider == null) == (it.destination == null))
        if (it.provider != null) PushDestination("0".repeat(32), it.provider!!, it.destination!!)
        require(it.key == null || it.destination != null)
    }
}

internal fun writeRegistration(state: RegistrationState): JSONObject = JSONObject()
    .put("ready", state.ready).put("baseline", state.baseline ?: JSONObject.NULL)
    .put("version", state.version ?: JSONObject.NULL).put("key", state.key ?: JSONObject.NULL)
    .put("provider", state.provider ?: JSONObject.NULL).put("destination", state.destination ?: JSONObject.NULL)
    .put("removalComplete", state.removalComplete)
