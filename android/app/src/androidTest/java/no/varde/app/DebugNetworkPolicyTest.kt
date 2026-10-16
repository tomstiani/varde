package no.varde.app

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugNetworkPolicyTest {
    @Test fun cleartextIsLimitedToTheLiteralUsbLoopbackHost() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        for (host in listOf("localhost", "127.0.0.2", "::1", "10.0.2.2", "192.168.1.1", "100.64.0.1", "example.com", "127.0.0.1.example.com")) {
            assertFalse(policy.isCleartextTrafficPermitted(host))
        }
    }
}
