package no.varde.app.inbox

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
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
        return request(connection, url, history = true) { json, _ ->
            val items = json.getJSONArray("items")
            require(items.length() <= 50)
            require(json.has("nextCursor") && (json.isNull("nextCursor") || json.get("nextCursor") is String))
            val rows = (0 until items.length()).map { parseRow(items.getJSONObject(it)) }
            require(rows.map { it.id }.distinct().size == rows.size)
            HistoryPage(rows, if (json.isNull("nextCursor")) null else json.getString("nextCursor"))
        }
    }

    override suspend fun read(connection: Connection, id: String): AlertDetail =
        request(connection, connection.endpoint("v1", "alerts", id)) { json, _ ->
            require(json.text("id") == id)
            val original = json.getJSONObject("original")
            require(original.get("schemaVersion") == 1)
            AlertDetail(parseRow(json, original), original.text("summary"))
        }

    override suspend fun checkpoint(connection: Connection): String =
        request(connection, connection.endpoint("v1", "changes", "checkpoint"), recovery = true, method = "POST") { json, _ -> json.cursor("cursor") }

    override suspend fun changes(connection: Connection, cursor: String): ChangePage =
        request(connection, connection.endpoint("v1", "changes").newBuilder().addQueryParameter("cursor", cursor).addQueryParameter("limit", "50").build(), recovery = true) { json, _ ->
            val values = json.getJSONArray("items")
            require(values.length() <= 50)
            val items = (0 until values.length()).map { index ->
                val item = values.getJSONObject(index)
                val id = item.text("alertId")
                val kind = item.text("kind")
                val revision = item.get("revision")
                require(id.isNotBlank() && id.length <= 128 && revision is Int && revision >= 0)
                require(kind in setOf("accepted", "presentation") && ((kind == "accepted") == (revision == 0)))
                ChangeEntry(id, kind, revision)
            }
            require(items.map { it.alertId to it.revision }.distinct().size == items.size)
            val next = json.cursor("nextCursor")
            val more = json.get("hasMore")
            require(more is Boolean && (!more || items.isNotEmpty()))
            require((items.isEmpty() && !more) || next != cursor)
            ChangePage(items, next, more)
        }

    override suspend fun pushRegistration(connection: Connection): PushRegistration =
        request(connection, connection.endpoint("v1", "installation", "push"), registration = true, parse = ::parsePushRegistration)

    override suspend fun putPush(connection: Connection, mutation: PushMutation, destination: PushDestination): PushRegistration {
        val body = JSONObject().put("registrationId", destination.registrationId).put("provider", destination.provider)
            .put("destination", destination.destination).toString().toByteArray(Charsets.UTF_8)
        if (body.size > 16_384) throw InboxFailure(Problem.CONFIGURATION)
        return request(connection, connection.endpoint("v1", "installation", "push"), registration = true, method = "PUT", body = body, mutation = mutation) { json, response ->
            parsePushRegistration(json, response).also {
                require(it.enabled && it.registrationId == destination.registrationId && it.provider == destination.provider)
                require(mutation.version < Long.MAX_VALUE && it.version == mutation.version + 1)
            }
        }
    }

    override suspend fun deletePush(connection: Connection, mutation: PushMutation): PushRegistration =
        request(connection, connection.endpoint("v1", "installation", "push"), registration = true, method = "DELETE", mutation = mutation) { json, response ->
            parsePushRegistration(json, response).also {
                require(!it.enabled && mutation.version < Long.MAX_VALUE && it.version == mutation.version + 1)
            }
        }

    private suspend fun <T> request(
        connection: Connection,
        url: HttpUrl,
        history: Boolean = false,
        recovery: Boolean = false,
        registration: Boolean = false,
        method: String = "GET",
        body: ByteArray? = null,
        mutation: PushMutation? = null,
        parse: (JSONObject, Response) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${connection.secret}")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .apply {
                    mutation?.let {
                        header("If-Match", "\"${it.version}\"")
                        header("Idempotency-Key", it.key)
                    }
                }
                .method(method, when {
                    body != null -> body.toRequestBody("application/json; charset=utf-8".toMediaType())
                    method == "POST" -> byteArrayOf().toRequestBody()
                    else -> null
                }).build()
            val call = transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(transportFailure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            checkStatus(it, history, recovery, registration)
                            val body = it.body ?: throw InboxFailure(Problem.RESPONSE)
                            val source = body.source()
                            if (source.request(524_289)) throw InboxFailure(Problem.RESPONSE)
                            parse(JSONObject(source.readUtf8()), it)
                        }
                        continuation.resume(result)
                    } catch (failure: InboxFailure) {
                        continuation.resumeWithException(failure)
                    } catch (error: IOException) {
                        continuation.resumeWithException(transportFailure(error))
                    } catch (_: Exception) {
                        continuation.resumeWithException(InboxFailure(Problem.RESPONSE))
                    }
                }
            })
        }
}

private fun transportFailure(error: IOException) = InboxFailure(if (error is SSLException) Problem.TLS else Problem.NETWORK)

private fun checkStatus(response: Response, history: Boolean, recovery: Boolean, registration: Boolean) {
    if (registration && response.code in setOf(409, 412)) throw InboxFailure(registrationError(response))
    if (recovery && response.code in setOf(400, 409)) throw InboxFailure(recoveryError(response))
    if (response.code == 400) throw InboxFailure(if (registration) Problem.RESPONSE else historyError(response))
    val problem = when (response.code) {
        200 -> return
        in 300..399 -> Problem.REDIRECT
        401 -> Problem.AUTHENTICATION
        403 -> Problem.PERMISSION
        404 -> if (history || recovery || registration) Problem.RESPONSE else Problem.NOT_FOUND
        in 500..599 -> Problem.NETWORK
        else -> Problem.RESPONSE
    }
    throw InboxFailure(problem)
}

private fun historyError(response: Response): Problem = try {
    val source = response.body?.source()
    if (source != null && !source.request(4097) && JSONObject(source.readUtf8()).opt("error") == "invalid_cursor") {
        Problem.HISTORY_CURSOR
    } else Problem.RESPONSE
} catch (_: Exception) {
    Problem.RESPONSE
}

private fun recoveryError(response: Response): Problem = try {
    val source = response.body?.source()
    if (source == null || source.request(4097)) Problem.RESPONSE
    else {
        val category = JSONObject(source.readUtf8()).opt("error")
        if ((response.code == 400 && category == "invalid_cursor") || (response.code == 409 && category == "sync_reset_required")) {
            Problem.RECOVERY_CURSOR
        } else Problem.RESPONSE
    }
} catch (_: Exception) { Problem.RESPONSE }

private fun JSONObject.cursor(name: String): String = text(name).also {
    require(it.matches(Regex("[A-Za-z0-9_.-]{1,2048}")))
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
