package no.varde.app.inbox

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpBackend(client: OkHttpClient = OkHttpClient()) : Backend {
    private val transport = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun history(connection: Connection, cursor: String?): HistoryPage {
        val url = connection.endpoint("v1", "alerts").newBuilder()
            .addQueryParameter("order", "acceptedAt")
            .addQueryParameter("limit", "50")
            .apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
        return request(connection, url) { json ->
            val items = json.getJSONArray("items")
            require(items.length() <= 50)
            require(json.has("nextCursor") && (json.isNull("nextCursor") || json.get("nextCursor") is String))
            val rows = (0 until items.length()).map { parseRow(items.getJSONObject(it)) }
            require(rows.map { it.id }.distinct().size == rows.size)
            HistoryPage(rows, if (json.isNull("nextCursor")) null else json.getString("nextCursor"))
        }
    }

    override suspend fun read(connection: Connection, id: String): AlertDetail =
        request(connection, connection.endpoint("v1", "alerts", id)) { json ->
            require(json.text("id") == id)
            val original = json.getJSONObject("original")
            require(original.get("schemaVersion") == 1)
            AlertDetail(parseRow(json, original), original.text("summary"))
        }

    private suspend fun <T> request(connection: Connection, url: HttpUrl, parse: (JSONObject) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${connection.secret}")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store").build()
            val call = transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(InboxFailure(Problem.NETWORK))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            checkStatus(it.code)
                            val body = it.body ?: throw InboxFailure(Problem.RESPONSE)
                            val source = body.source()
                            if (source.request(524_289)) throw InboxFailure(Problem.RESPONSE)
                            parse(JSONObject(source.readUtf8()))
                        }
                        continuation.resume(result)
                    } catch (failure: InboxFailure) {
                        continuation.resumeWithException(failure)
                    } catch (_: IOException) {
                        continuation.resumeWithException(InboxFailure(Problem.NETWORK))
                    } catch (_: Exception) {
                        continuation.resumeWithException(InboxFailure(Problem.RESPONSE))
                    }
                }
            })
        }
}

private fun checkStatus(code: Int) {
    val problem = when (code) {
        200 -> return
        in 300..399 -> Problem.REDIRECT
        401 -> Problem.AUTHENTICATION
        403 -> Problem.PERMISSION
        404 -> Problem.NOT_FOUND
        in 500..599 -> Problem.NETWORK
        else -> Problem.RESPONSE
    }
    throw InboxFailure(problem)
}

private fun JSONObject.text(name: String): String = get(name).let {
    require(it is String)
    it
}

private fun parseRow(json: JSONObject, original: JSONObject = json): AlertRow {
    val presentation = json.getJSONObject("presentation")
    val revision = presentation.get("revision")
    require(revision is Int && revision >= 0)
    val state = presentation.text("state")
    require(state in setOf("pending", "fallback"))
    val priority = original.text("priority")
    require(priority in setOf("low", "normal", "high", "critical"))
    val id = json.text("id")
    require(id.isNotBlank())
    return AlertRow(id, json.text("acceptedAt"), original.text("title"), priority, original.text("observedAt"), Presentation(state, revision))
}
