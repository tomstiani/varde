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
import no.varde.app.inbox.*

class NotificationRuntime(
    context: Context,
    settings: ConnectionSettings = SecureConnectionSettings(context),
    backend: Backend = HttpBackend(),
    directory: File = File(context.noBackupFilesDir, "notifications"),
    output: NotificationOutput = AndroidNotificationOutput(context),
) {
    private val sourceSettings = settings
    private val mutableProblem = MutableStateFlow<Problem?>(null)
    val problem = mutableProblem.asStateFlow()
    val settings: ConnectionSettings = object : ConnectionSettings {
        override suspend fun load() = sourceSettings.load()
        override suspend fun save(connections: List<Connection>) {
            sourceSettings.save(connections)
            try { coordinator.synchronize() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProblem.value = Problem.SETTINGS }
        }
    }
    val backend: Backend = BoundedBackend(backend) { null }
    val coordinator = NotificationCoordinator(this.settings, this.backend, directory, output)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
