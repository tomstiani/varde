package no.varde.app.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class SecureSettingsTest {
    @Test fun settingsSurviveRecreationAndRemovalWithoutPlaintextSecrets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "test-connection.enc")
        file.delete()
        try {
            val home = Connection.create("Private lab", "https://example.com", "SENTINEL-private-token")
            val work = Connection.create("Work", "https://work.example.com", "SENTINEL-work-token")
            val settings = SecureConnectionSettings(context, file.name)
            assertTrue(settings.load().isEmpty())
            settings.save(listOf(home, work))
            val restored = SecureConnectionSettings(context, file.name).load()
            assertEquals(listOf(home.id, work.id), restored.map { it.id })
            assertEquals(home.name, restored.first().name)
            assertEquals(home.secret, restored.first().secret)
            val persisted = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(persisted.contains("SENTINEL-private-token"))
            assertFalse(persisted.contains("SENTINEL-work-token"))
            assertFalse(persisted.contains("Private lab"))
            settings.save(listOf(home))
            assertEquals(listOf(home.id), settings.load().map { it.id })
            settings.save(emptyList())
            assertTrue(settings.load().isEmpty())
            val tampered = file.readBytes()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            file.writeBytes(tampered)
            try { settings.load(); fail("Corrupt settings accepted") } catch (failure: InboxFailure) {
                assertEquals(Problem.SETTINGS, failure.problem)
            }
        } finally { file.delete() }
    }

    @Test fun upgradesAnEncryptedSingleConnectionWithoutChangingIdentityOrSecret() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "test-legacy-connection.enc")
        file.delete()
        try {
            val home = Connection.create("Home", "https://example.com", "legacy-token")
            val settings = SecureConnectionSettings(context, file.name)
            settings.save(listOf(home))
            // The prior release's encrypted JSON is a migration fixture, not a new storage Interface.
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey("${context.packageName}.connection.v1", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val legacy = JSONObject().put("id", home.id).put("name", home.name)
                .put("url", home.baseUrl.toString()).put("secret", home.secret)
            file.writeBytes(cipher.iv + cipher.doFinal(legacy.toString().toByteArray(Charsets.UTF_8)))
            val restored = settings.load().single()
            assertEquals(home.id, restored.id)
            assertEquals(home.secret, restored.secret)
            val work = Connection.create("Work", "https://work.example.com", "work-token")
            settings.save(listOf(restored, work))
            assertEquals(listOf(home.id, work.id), SecureConnectionSettings(context, file.name).load().map { it.id })
        } finally { file.delete() }
    }
}
