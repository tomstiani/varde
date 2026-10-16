package no.varde.app.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SecureSettingsTest {
    @Test fun settingsSurviveRecreationWithoutPlaintextSecrets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "test-connection.enc")
        file.delete()
        try {
            val connection = Connection.create("Private lab", "https://example.com", "SENTINEL-private-token")
            val settings = SecureConnectionSettings(context, "test-connection.enc")
            assertNull(settings.load())
            settings.save(connection)
            val restored = SecureConnectionSettings(context, "test-connection.enc").load()!!
            assertEquals(connection.id, restored.id)
            assertEquals(connection.name, restored.name)
            assertEquals(connection.secret, restored.secret)
            val persisted = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(persisted.contains("SENTINEL-private-token"))
            assertFalse(persisted.contains("Private lab"))
            file.writeBytes(byteArrayOf(1, 2, 3))
            try { settings.load(); fail("Corrupt settings accepted") } catch (failure: InboxFailure) {
                assertEquals(Problem.SETTINGS, failure.problem)
            }
        } finally { file.delete() }
    }
}
