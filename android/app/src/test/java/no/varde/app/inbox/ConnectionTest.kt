package no.varde.app.inbox

import org.junit.Assert.*
import org.junit.Test

class ConnectionTest {
    @Test fun connectionNormalizesEndpointWithoutExposingCredential() {
        val connection = Connection.create(" Home ", "https://EXAMPLE.com:443/varde/", "private-token")
        assertEquals("Home", connection.name)
        assertEquals("https://example.com/varde/", connection.baseUrl.toString())
        assertFalse(connection.toString().contains("private-token"))
        assertEquals("https://example.com/varde/v1/alerts", connection.endpoint("v1", "alerts").toString())
    }

    @Test fun unsafeConfigurationHasSafeErrors() {
        for (url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com?secret=value", "https://example.com#fragment")) {
            try {
                Connection.create("Home", url, "private-token")
                fail("Unsafe URL accepted")
            } catch (failure: InboxFailure) {
                assertEquals(Problem.CONFIGURATION, failure.problem)
                assertFalse(failure.message.orEmpty().contains("private-token"))
                assertFalse(failure.message.orEmpty().contains("secret=value"))
            }
        }
    }
}
