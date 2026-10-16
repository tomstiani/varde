package no.varde.app.inbox

import org.junit.Assert.*
import org.junit.Test

class UsbConnectionTest {
    @Test fun debugUsbAllowsOnlyLiteralIpv4LoopbackHttp() {
        val connection = Connection.create("USB", "http://127.0.0.1:18081/", "synthetic-token")
        assertEquals("http://127.0.0.1:18081/v1/alerts", connection.endpoint("v1", "alerts").toString())
        for (url in listOf(
            "http://localhost:18081", "http://127.0.0.2", "http://127.1", "http://2130706433",
            "http://[::1]", "http://10.0.2.2", "http://192.168.1.1", "http://100.64.0.1",
            "http://127.0.0.1.example.com", "http://127.0.0.1@evil.example", "http://user:pass@127.0.0.1",
        )) {
            try {
                Connection.create("USB", url, "synthetic-token")
                fail("Non-USB HTTP destination accepted")
            } catch (failure: InboxFailure) {
                assertEquals(Problem.CONFIGURATION, failure.problem)
            }
        }
    }
}
