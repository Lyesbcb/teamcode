package me.nathanfallet.teamcode.domain.gateway

import kotlinx.coroutines.flow.Flow

/** Protocol bytes remain opaque on native passthrough paths. */
data class GenerationRequest(val body: ByteArray, val headers: Map<String, String>)
data class GenerationHead(val status: Int, val contentType: String)

interface GatewayService {
    suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, Flow<ByteArray>) -> Unit)
    fun status(): GatewaySnapshot
}

data class GatewaySnapshot(val mode: String, val active: Int, val completed: Long)
