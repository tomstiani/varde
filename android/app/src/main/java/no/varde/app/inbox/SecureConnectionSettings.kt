package no.varde.app.inbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConnectionSettings(context: Context, fileName: String = "connection.enc") : ConnectionSettings {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val alias = "${context.packageName}.connection.v1"

    override suspend fun load(): List<Connection> = withContext(Dispatchers.IO) {
        safely {
            val encrypted = try { file.readFully() } catch (_: java.io.FileNotFoundException) { return@safely emptyList() }
            require(encrypted.size in 29..1_048_576)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            val json = JSONObject(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)).toString(Charsets.UTF_8))
            val connections = if (!json.has("version")) listOf(decodeConnection(json)) else {
                require(json.getInt("version") == 2)
                val entries = json.getJSONArray("connections")
                (0 until entries.length()).map { decodeConnection(entries.getJSONObject(it)) }
            }
            validateConnections(connections)
            connections
        }
    }

    override suspend fun save(connections: List<Connection>) = withContext(Dispatchers.IO) {
        safely {
            validateConnections(connections)
            val entries = JSONArray()
            connections.forEach { connection ->
                entries.put(JSONObject().put("id", connection.id).put("name", connection.name)
                    .put("url", connection.baseUrl.toString()).put("secret", connection.secret))
            }
            val json = JSONObject().put("version", 2).put("connections", entries)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            require(cipher.iv.size == 12)
            val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            require(encrypted.size <= 1_048_576)
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        }
    }

    private fun decodeConnection(json: JSONObject) = Connection.create(
        json.getString("name"), json.getString("url"), json.getString("secret"), json.getString("id"),
    )

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun <T> safely(operation: () -> T): T = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw InboxFailure(Problem.SETTINGS)
    }
}
