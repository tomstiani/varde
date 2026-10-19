package no.varde.app.inbox

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class GoBackendFixture(private val backendDirectory: File) : Closeable {
    private val directory = Files.createTempDirectory("varde-history-test-").toFile()
    private val executable = File(directory, "varde")
    private val database = File(directory, "alerts.sqlite")
    private var process: Process? = null
    val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
    @Volatile lateinit var address: HttpUrl
        private set
    private lateinit var senderSecret: String
    private lateinit var installationIdentity: String
    private lateinit var installationCredential: String
    lateinit var installationSecret: String
        private set

    init {
        try {
            command(listOf("go", "build", "-o", executable.absolutePath, "./cmd/varde"), 120)
            senderSecret = issue("sender")
            installationSecret = issue("installation")
            start()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun submit(number: Int): Pair<Int, String> {
        val original = JSONObject().put("schemaVersion", 1).put("eventId", "event-$number")
            .put("title", "Alert $number").put("summary", "Original $number")
            .put("priority", "normal").put("observedAt", "2026-01-01T12:00:00Z")
        val request = Request.Builder().url(address.resolve("v1/alerts")!!)
            .header("Authorization", "Bearer $senderSecret")
            .post(original.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            check(response.code == 200 || response.code == 201) { "Synthetic alert was not accepted" }
            response.code to JSONObject(response.body!!.string()).getString("id")
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun pause() = stop()

    fun resume() { check(process == null); start() }

    fun additionalInstallation(): String {
        val identity = JSONObject(operator("identity", "create", "installation", "test-other-installation")).getString("id")
        return JSONObject(operator("credential", "issue", identity)).getString("secret")
    }

    fun rotateInstallation() {
        val replacement = JSONObject(operator("credential", "issue", installationIdentity))
        operator("credential", "revoke", installationCredential)
        installationCredential = replacement.getString("id")
        installationSecret = replacement.getString("secret")
    }

    private fun issue(role: String): String {
        val identity = JSONObject(operator("identity", "create", role, "test-$role")).getString("id")
        val credential = JSONObject(operator("credential", "issue", identity))
        if (role == "installation") {
            installationIdentity = identity
            installationCredential = credential.getString("id")
        }
        return credential.getString("secret")
    }

    private fun operator(vararg arguments: String) = command(listOf(executable.absolutePath, "-db", database.absolutePath) + arguments, 30)

    private fun command(arguments: List<String>, timeoutSeconds: Long): String {
        val child = ProcessBuilder(arguments).directory(backendDirectory)
            .redirectError(File(directory, "command.err")).start()
        try {
            check(child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Go fixture command timed out" }
            check(child.exitValue() == 0) { "Go fixture command failed" }
            // Credential issuance stays in memory, never in a redirected output file or argument.
            return child.inputStream.use { it.readNBytes(65_536).toString(Charsets.UTF_8) }
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
        }
    }

    private fun start() {
        val child = ProcessBuilder(executable.absolutePath, "-db", database.absolutePath, "serve", "-listen", "127.0.0.1:0")
            .redirectError(File(directory, "serve.err")).start()
        process = child
        val readiness = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
        val listening = JSONObject(readiness).getString("address")
        address = "http://$listening/".toHttpUrl()
    }

    private fun stop() {
        val child = process ?: return
        child.destroy()
        if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly().waitFor()
        child.inputStream.close()
        process = null
    }

    override fun close() {
        stop()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        directory.deleteRecursively()
    }
}
