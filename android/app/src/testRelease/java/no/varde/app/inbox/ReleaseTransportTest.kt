package no.varde.app.inbox

import org.junit.Assert.*
import org.junit.Test

class ReleaseTransportTest {
    @Test fun releaseRejectsAllHttpIncludingUsbLoopback() {
        for (url in listOf("http://127.0.0.1:18081/", "http://localhost", "http://[::1]", "http://example.com")) {
            try {
                Connection.create("Release", url, "synthetic-token")
                fail("Release accepted cleartext HTTP")
            } catch (failure: InboxFailure) {
                assertEquals(Problem.CONFIGURATION, failure.problem)
            }
        }
        assertEquals("https", Connection.create("Release", "https://example.com", "synthetic-token").baseUrl.scheme)
    }
}
