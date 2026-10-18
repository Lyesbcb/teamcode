package me.nathanfallet.teamcode.domain.gateway

import kotlinx.coroutines.flow.Flow
import me.nathanfallet.teamcode.domain.team.Principal

/** Protocol bytes remain opaque on native passthrough paths. */
data class GenerationRequest(val body: ByteArray, val headers: Map<String, String>, val principal: Principal? = null, val protocol: WireProtocol = WireProtocol.MESSAGES)
enum class WireProtocol { MESSAGES, RESPONSES }
data class GenerationHead(val status: Int, val contentType: String, val headers: Map<String, String> = emptyMap())

interface GatewayService {
    suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, Flow<ByteArray>) -> Unit)
    suspend fun status(): GatewaySnapshot
}

data class GatewaySnapshot(val mode: String, val active: Int, val completed: Long)
