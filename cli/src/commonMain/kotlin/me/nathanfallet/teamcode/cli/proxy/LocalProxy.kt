package me.nathanfallet.teamcode.cli.proxy

import io.ktor.http.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import me.nathanfallet.teamcode.client.TeamCodeClient

/** Fixed Messages/Responses routes only. No CONNECT or arbitrary destination. */
fun Application.localProxy(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int = 2 * 1024 * 1024) {
    require(localToken.length >= 32) { "A local proxy token of at least 32 characters is required." }
    routing {
        for (path in listOf("/v1/messages", "/v1/responses")) {
        post(path) {
            val auth = call.request.header(HttpHeaders.Authorization)
            val key = call.request.header("x-api-key")
            if (!equalToken(auth?.removePrefix("Bearer "), localToken) && !equalToken(key, localToken)) {
                call.respondText("A local TeamCode capability is required.", status = HttpStatusCode.Unauthorized)
                return@post
            }
            val host = call.request.local.serverHost
            if (host !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                call.respondText("Browser or non-loopback destinations are not accepted.", status = HttpStatusCode.Forbidden)
                return@post
            }
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                call.respondText("A JSON body is required.", status = HttpStatusCode.UnsupportedMediaType)
                return@post
            }
            val buffer = ByteArray(8192)
            val chunks = mutableListOf<ByteArray>()
            var size = 0
            val channel = call.receiveChannel()
            while (true) {
                val count = channel.readAvailable(buffer, 0, minOf(buffer.size, maximumBodyBytes - size + 1))
                if (count < 0) break
                if (count == 0) continue
                if (size + count > maximumBodyBytes) {
                    call.respondText("Request is too large.", status = HttpStatusCode.PayloadTooLarge)
                    return@post
                }
                size += count
                chunks.add(buffer.copyOf(count))
            }
            val body = ByteArray(size)
            var offset = 0
            for (chunk in chunks) { chunk.copyInto(body, offset); offset += chunk.size }
            val headers = TeamCodeClient.FORWARDED_HEADERS.mapNotNull { name -> call.request.header(name)?.let { name to it } }.toMap()
            client.forwardInference(path, body, headers) { upstream ->
                listOf("retry-after", "x-teamcode-request-id").forEach { name -> upstream.headers[name]?.let { call.response.header(name, it) } }
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Accel-Buffering", "no")
                // Ktor may schedule the writer after respond returns. Keep the SDK response
                // scope open until the downstream has actually consumed/cancelled the body.
                val consumed = CompletableDeferred<Unit>()
                call.respondBytesWriter(upstream.contentType() ?: ContentType.Application.OctetStream, upstream.status) {
                    try {
                        upstream.bodyAsChannel().copyTo(this)
                        consumed.complete(Unit)
                    } catch (error: Throwable) {
                        consumed.completeExceptionally(error)
                        throw error
                    }
                }
                consumed.await()
            }
        }
        }
    }
}

private fun equalToken(presented: String?, expected: String): Boolean {
    if (presented == null) return false
    var difference = presented.length xor expected.length
    for (index in expected.indices) {
        difference = difference or (expected[index].code xor (presented.getOrNull(index)?.code ?: 0))
    }
    return difference == 0
}
