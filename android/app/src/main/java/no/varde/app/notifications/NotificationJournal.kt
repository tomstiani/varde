package no.varde.app.notifications

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

internal data class NotificationSession(
    val connectionId: String,
    val registrationId: String,
    val access: String,
    var retired: Boolean = false,
    var cursor: String? = null,
    var pendingCount: Int = 0,
    var summaryTarget: String? = null,
    var summaryCount: Int = 0,
    var summaryDismissed: Boolean = false,
)
internal data class NotificationEntry(val registrationId: String, val alertId: String, val targetId: String, var dismissed: Boolean = false)
internal class NotificationState(
    val sessions: MutableList<NotificationSession> = mutableListOf(),
    val entries: MutableList<NotificationEntry> = mutableListOf(),
)

internal class NotificationJournal(private val directory: File) {
    private val file = File(directory, "metadata.json")

    fun load(): NotificationState {
        if (!file.exists()) return NotificationState()
        require(file.length() <= 8_388_608)
        val json = JSONObject(file.readText())
        val version = json.getInt("version")
        require(version in 1..2)
        val sessions = json.getJSONArray("sessions")
        val entries = json.getJSONArray("entries")
        return NotificationState(
            (0 until sessions.length()).map { index ->
                val item = sessions.getJSONObject(index)
                NotificationSession(item.getString("connection"), item.getString("registration"), item.getString("access"), item.getBoolean("retired"),
                    cursor = if (item.isNull("cursor")) null else item.getString("cursor"),
                    pendingCount = item.optInt("pendingCount", 0),
                    summaryTarget = if (item.isNull("summaryTarget")) null else item.getString("summaryTarget"),
                    summaryCount = item.optInt("summaryCount", 0),
                    summaryDismissed = item.optBoolean("summaryDismissed", false)).also { session ->
                    require(session.pendingCount >= 0 && session.summaryCount >= 0)
                    require(session.registrationId.matches(Regex("[0-9a-f]{32}")) && session.access.matches(Regex("[0-9a-f]{64}")))
                    require(session.cursor == null || session.cursor!!.matches(Regex("[A-Za-z0-9_.-]{1,2048}")))
                    require(session.summaryTarget == null || session.summaryTarget!!.matches(Regex("[0-9a-f]{32}")))
                    if (version == 2) {
                        require(item.get("pendingCount") is Int && item.get("summaryCount") is Int && item.get("summaryDismissed") is Boolean)
                        require(item.isNull("cursor") || item.get("cursor") is String)
                        require(item.isNull("summaryTarget") || item.get("summaryTarget") is String)
                    }
                }
            }.toMutableList(),
            (0 until entries.length()).map { index ->
                val item = entries.getJSONObject(index)
                NotificationEntry(item.getString("registration"), item.getString("alert"), item.getString("target"), item.getBoolean("dismissed"))
            }.toMutableList(),
        )
    }

    fun save(state: NotificationState) {
        val sessions = JSONArray()
        state.sessions.forEach {
            sessions.put(JSONObject().put("connection", it.connectionId).put("registration", it.registrationId)
                .put("access", it.access).put("retired", it.retired).put("cursor", it.cursor ?: JSONObject.NULL)
                .put("pendingCount", it.pendingCount).put("summaryTarget", it.summaryTarget ?: JSONObject.NULL)
                .put("summaryCount", it.summaryCount).put("summaryDismissed", it.summaryDismissed))
        }
        val entries = JSONArray()
        state.entries.forEach {
            entries.put(JSONObject().put("registration", it.registrationId).put("alert", it.alertId)
                .put("target", it.targetId).put("dismissed", it.dismissed))
        }
        val bytes = JSONObject().put("version", 2).put("sessions", sessions).put("entries", entries).toString().toByteArray()
        require(bytes.size <= 8_388_608)
        check(directory.isDirectory || directory.mkdirs())
        val pending = File(directory, "metadata.new")
        check(pending.createNewFile() || pending.isFile)
        check(pending.setReadable(false, false) && pending.setReadable(true, true))
        check(pending.setWritable(false, false) && pending.setWritable(true, true))
        FileOutputStream(pending).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
