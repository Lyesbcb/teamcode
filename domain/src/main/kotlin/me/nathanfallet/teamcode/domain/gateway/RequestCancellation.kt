package me.nathanfallet.teamcode.domain.gateway

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.team.requireReady

/** Bounded, expiring cancellation markers shared by all replicas; no request bodies. */
interface RequestCancellationStore {
    suspend fun cancel(actor: Principal, requestId: String)
    suspend fun isCancelled(actor: Principal, requestId: String): Boolean
    suspend fun maintain()
}

class RequestCancellationService(private val store: RequestCancellationStore) {
    suspend fun cancel(actor: Principal, requestId: String) {
        actor.requireReady()
        validateId(requestId)
        store.cancel(actor, requestId)
    }

    suspend fun <T> run(actor: Principal, requestId: String, block: suspend () -> T): T {
        actor.requireReady()
        validateId(requestId)
        if (store.isCancelled(actor, requestId)) throw CancellationException("The inference was cancelled.")
        return coroutineScope {
            val work = async { block() }
            val monitor = launch {
                while (isActive) {
                    delay(POLL_MILLIS)
                    if (store.isCancelled(actor, requestId)) {
                        work.cancel(CancellationException("The inference was cancelled."))
                        break
                    }
                }
            }
            try { work.await() } finally { monitor.cancelAndJoin() }
        }
    }

    companion object {
        const val POLL_MILLIS = 500L
        const val RETENTION_MILLIS = 600_000L
        const val MEMBER_LIMIT = 256L
        const val TEAM_LIMIT = 4096L
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private fun validateId(id: String) {
            if (!idPattern.matches(id)) throw TeamFailure("invalid_request_id", 400, "A lowercase UUID request identifier is required.")
        }
    }
}

/** Covers admission, fallback and stream consumption with one cancellation scope. */
class CancellableGateway(private val delegate: GatewayService, private val cancellation: RequestCancellationService) : GatewayService {
    override suspend fun status() = delegate.status()
    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, Flow<ByteArray>) -> Unit) {
        val id = request.clientRequestId
        val actor = request.principal
        if (actor == null) {
            if (id != null) throw TeamFailure("authentication_required", 401, "A member session is required.")
            delegate.forward(request, receive)
        } else {
            // Legacy/direct clients have no control ID, but their sessions must still be
            // revalidated while streaming. This server-only ID never reaches a provider.
            cancellation.run(actor, id ?: Uuid.random().toString()) { delegate.forward(request, receive) }
        }
    }
}
