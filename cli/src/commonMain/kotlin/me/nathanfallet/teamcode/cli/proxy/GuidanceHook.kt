package me.nathanfallet.teamcode.cli.proxy

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.cli.agents.HookGuidance
import me.nathanfallet.teamcode.client.TeamCodeClient

/** Tool inputs and transcript paths stop here. Only the session identifier reaches TeamCode. */
internal fun Route.guidanceHook(client: TeamCodeClient, localToken: String) {
    post("/_teamcode/hooks/claude") {
        if (!equalToken(call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer "), localToken)) {
            call.respond(HttpStatusCode.Unauthorized); return@post
        }
        if (call.request.local.serverHost !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
            call.respond(HttpStatusCode.Forbidden); return@post
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val bridge = HookGuidance(client)
        var emitted: HookGuidance.Emission? = null
        var output = "{}"
        try {
            withTimeoutOrNull(2_500) {
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json) return@withTimeoutOrNull
                val channel = call.receiveChannel()
                val buffer = ByteArray(65_537)
                var size = 0
                while (size < buffer.size) {
                    val count = channel.readAvailable(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > 65_536) return@withTimeoutOrNull
                emitted = bridge.prepare(buffer.copyOf(size))
                output = emitted?.output ?: "{}"
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Advice failures never block work or expose diagnostics to model context. */ }
        call.respondText(output, ContentType.Application.Json)
        emitted?.let { bridge.acknowledge(it) }
    }
}
