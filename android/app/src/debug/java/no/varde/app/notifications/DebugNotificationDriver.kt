package no.varde.app.notifications

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import no.varde.app.inbox.Backend
import no.varde.app.inbox.ConnectionSettings

object DebugNotificationDriver {
    suspend fun signal(context: Context, connectionId: String, alertId: String) {
        val coordinator = NotificationRuntime.get(context).coordinator
        val registration = coordinator.currentRegistration(connectionId) ?: UUID.randomUUID().toString().replace("-", "").also {
            coordinator.activate(connectionId, it)
        }
        coordinator.receive(AlertSignal("1", registration, alertId))
    }

    fun install(context: Context, settings: ConnectionSettings, backend: Backend, directory: File): Closeable {
        val previous = NotificationRuntime.instance
        val runtime = NotificationRuntime(context, settings, backend, directory)
        NotificationRuntime.instance = runtime
        return Closeable {
            runtime.scope.cancel()
            NotificationRuntime.instance = previous
        }
    }
}
