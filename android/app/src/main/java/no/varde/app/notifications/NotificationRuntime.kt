package no.varde.app.notifications

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import no.varde.app.inbox.*

class NotificationRuntime(
    context: Context,
    settings: ConnectionSettings = SecureConnectionSettings(context),
    backend: Backend = HttpBackend(),
    directory: File = File(context.noBackupFilesDir, "notifications"),
    output: NotificationOutput = AndroidNotificationOutput(context),
    private val tokenSource: PushTokenSource? = PushTokenSource { null },
) {
    private val sourceSettings = settings
    private val mutableProblem = MutableStateFlow<Problem?>(null)
    val problem = mutableProblem.asStateFlow()
    val settings: ConnectionSettings = object : ConnectionSettings {
        override suspend fun load() = sourceSettings.load()
        override suspend fun save(connections: List<Connection>) {
            val previous = sourceSettings.load()
            sourceSettings.save(connections)
            try {
                coordinator.synchronize()
                if (tokenSource != null) {
                    val obsolete = previous.filter { old -> connections.none { it.id == old.id && it.sameAccess(old) } }
                    if (obsolete.isNotEmpty()) launch { coordinator.unregisterObsolete(obsolete) }
                    connections.filter { connection -> previous.none { it.id == connection.id && it.sameAccess(connection) } }
                        .forEach { connection -> launch { refreshNotifications(connection.id) } }
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProblem.value = Problem.SETTINGS }
        }
    }
    val backend: Backend = BoundedBackend(backend) { null }
    val coordinator = NotificationCoordinator(this.settings, this.backend, directory, output)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    suspend fun refreshNotifications(connectionId: String? = null) = coroutineScope {
        if (tokenSource == null) {
            if (connectionId == null) coordinator.recoverAll() else coordinator.recover(connectionId)
            return@coroutineScope
        }
        coordinator.prepareNotifications()
        val connections = sourceSettings.load()
        this@coroutineScope.launch { coordinator.unregisterObsolete(connections) }
        val token = withTimeoutOrNull(15_000) { tokenSource.current() }
        connections.filter { connectionId == null || it.id == connectionId }.forEach { connection ->
            this@coroutineScope.launch {
                coordinator.register(connection.id, token)
                coordinator.recover(connection.id)
            }
        }
    }

    fun setNotificationsEnabled(connectionId: String, enabled: Boolean) = launch {
        coordinator.setNotificationsEnabled(connectionId, enabled)
        launch { coordinator.unregisterObsolete(sourceSettings.load()) }
        if (enabled) refreshNotifications(connectionId) else coordinator.recover(connectionId)
    }

    fun launch(operation: suspend () -> Unit) = scope.launch {
        try {
            operation()
            mutableProblem.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableProblem.value = Problem.SETTINGS
        }
    }

    companion object {
        internal var instance: NotificationRuntime? = null

        @Synchronized fun get(context: Context): NotificationRuntime = instance ?: NotificationRuntime(context.applicationContext).also { instance = it }
    }
}
