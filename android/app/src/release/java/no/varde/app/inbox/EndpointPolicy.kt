package no.varde.app.inbox

import okhttp3.HttpUrl

internal object EndpointPolicy {
    fun allows(url: HttpUrl): Boolean = url.isHttps
    val usbHint: String? = null
}
