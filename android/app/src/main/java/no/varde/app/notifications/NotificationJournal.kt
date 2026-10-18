package no.varde.app.notifications

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

internal data class NotificationSession(val connectionId: String, val registrationId: String, val access: String, var retired: Boolean = false)
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
        require(json.getInt("version") == 1)
        val sessions = json.getJSONArray("sessions")
        val entries = json.getJSONArray("entries")
        return NotificationState(
            (0 until sessions.length()).map { index ->
                val item = sessions.getJSONObject(index)
                NotificationSession(item.getString("connection"), item.getString("registration"), item.getString("access"), item.getBoolean("retired"))
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
                .put("access", it.access).put("retired", it.retired))
        }
        val entries = JSONArray()
        state.entries.forEach {
            entries.put(JSONObject().put("registration", it.registrationId).put("alert", it.alertId)
                .put("target", it.targetId).put("dismissed", it.dismissed))
        }
        val bytes = JSONObject().put("version", 1).put("sessions", sessions).put("entries", entries).toString().toByteArray()
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
