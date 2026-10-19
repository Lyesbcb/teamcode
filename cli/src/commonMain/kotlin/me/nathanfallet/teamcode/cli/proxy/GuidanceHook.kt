package me.nathanfallet.teamcode.cli.proxy

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.DirectiveClaimResponse
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.time.Clock

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
        var emitted: Pair<String, DirectiveClaimResponse>? = null
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
                val body = Json.parseToJsonElement(buffer.decodeToString(0, size)).jsonObject
                val event = (body["hook_event_name"] as? JsonPrimitive)?.contentOrNull
                if (event !in setOf("PreToolUse", "UserPromptSubmit")) return@withTimeoutOrNull
                val session = (body["session_id"] as? JsonPrimitive)?.contentOrNull ?: return@withTimeoutOrNull
                if (!Regex("[A-Za-z0-9._-]{1,128}").matches(session)) return@withTimeoutOrNull
                val claim = client.claimDirective(session, "EMITTED_TO_HOOK")
                val value = claim.directive ?: return@withTimeoutOrNull
                if (claim.claimId == null || value.expiresAtMillis <= Clock.System.now().toEpochMilliseconds()) return@withTimeoutOrNull
                output = buildJsonObject { putJsonObject("hookSpecificOutput") {
                    put("hookEventName", event)
                    put("additionalContext", "TeamCode capacity guidance: ${value.message}")
                } }.toString()
                emitted = session to claim
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Advice failures never block work or expose diagnostics to model context. */ }
        call.respondText(output, ContentType.Application.Json)
        emitted?.let { (session, claim) ->
            try { withTimeoutOrNull(750) { client.acknowledgeDirective(claim.directive!!.id, "EMITTED_TO_HOOK", session, claim.claimId) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Lost acknowledgments may cause a bounded retry after the claim lease. */ }
        }
    }
}
