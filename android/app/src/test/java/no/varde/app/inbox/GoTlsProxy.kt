package no.varde.app.inbox

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

internal class GoTlsProxy(fixture: GoBackendFixture, certificate: HeldCertificate) : Closeable {
    private val server = MockWebServer()
    val pageSizes = CopyOnWriteArrayList<Int>()
    val detailReads = java.util.concurrent.atomic.AtomicInteger()

    init {
        val trust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(trust.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val incoming = request.requestUrl!!
                if (incoming.encodedPath.startsWith("/v1/alerts/")) detailReads.incrementAndGet()
                val upstream = fixture.address.newBuilder().encodedPath(incoming.encodedPath).encodedQuery(incoming.encodedQuery).build()
                val forwarded = Request.Builder().url(upstream)
                    .header("Authorization", request.getHeader("Authorization").orEmpty())
                    .method(request.method!!, if (request.method == "POST") request.body.readByteArray().toRequestBody() else null).build()
                return try {
                    fixture.http.newCall(forwarded).execute().use { response ->
                        val body = response.body!!.string()
                        if (response.code == 200 && incoming.encodedPath == "/v1/alerts") {
                            check(incoming.queryParameter("limit") == "50" && incoming.queryParameter("order") == "acceptedAt")
                            pageSizes += JSONObject(body).getJSONArray("items").length()
                        }
                        MockResponse().setResponseCode(response.code).setHeader("Content-Type", "application/json").setBody(body)
                    }
                } catch (_: IOException) {
                    MockResponse().setResponseCode(503).setBody("""{"error":"upstream_unavailable"}""")
                }
            }
        }
        server.start()
    }

    fun connection(name: String, secret: String, id: String) = Connection.create(name, server.url("/").toString(), secret, id)
    override fun close() = server.close()
}
