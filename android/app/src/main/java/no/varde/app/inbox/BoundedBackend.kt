package no.varde.app.inbox

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

internal class BoundedBackend(private val delegate: Backend) : Backend {
    private val permits = Semaphore(4)

    override suspend fun history(connection: Connection, cursor: String?) = request {
        delegate.history(connection, cursor)
    }

    override suspend fun read(connection: Connection, id: String) = request {
        delegate.read(connection, id)
    }

    private suspend fun <T> request(operation: suspend () -> T): T = permits.withPermit {
        try {
            withTimeout(15_000) { operation() }
        } catch (_: TimeoutCancellationException) {
            throw InboxFailure(Problem.NETWORK)
        }
    }
}
