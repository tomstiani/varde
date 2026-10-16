package no.varde.app.inbox

import okhttp3.HttpUrl

internal object EndpointPolicy {
    fun allows(url: HttpUrl): Boolean = url.isHttps || (url.scheme == "http" && url.host == "127.0.0.1")
    val usbHint: String? = "USB debug testing also accepts http://127.0.0.1:PORT. Use synthetic acceptance credentials only."
}
