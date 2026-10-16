package no.varde.app.inbox

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

class Connection private constructor(
    val id: String,
    val name: String,
    val baseUrl: HttpUrl,
    internal val secret: String,
) {
    fun endpoint(vararg segments: String): HttpUrl = baseUrl.newBuilder().apply {
        segments.forEach { addPathSegment(it) }
    }.build()

    override fun toString() = "Connection(id=$id)"

    companion object {
        fun create(name: String, url: String, secret: String, id: String = UUID.randomUUID().toString()): Connection {
            val label = name.trim()
            val parsed = url.trim().toHttpUrlOrNull()
            if (label.isBlank() || label.codePointCount(0, label.length) > 80 ||
                parsed == null || !EndpointPolicy.allows(parsed) || parsed.username.isNotEmpty() ||
                parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null ||
                secret.isEmpty() || secret.any { it.code !in 33..126 } || id.isBlank()
            ) throw InboxFailure(Problem.CONFIGURATION)
            val path = parsed.encodedPath.trimEnd('/') + "/"
            return Connection(id, label, parsed.newBuilder().encodedPath(path).build(), secret)
        }
    }
}
