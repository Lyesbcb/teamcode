package me.nathanfallet.teamcode.cli.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.DirectiveClaimResponse
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.time.Clock

/** Shared hook protocol. Discard prompts, tool arguments and transcript paths locally. */
internal class HookGuidance(private val client: TeamCodeClient) {
    class Emission(val session: String, val claim: DirectiveClaimResponse, val output: String)

    suspend fun prepare(input: ByteArray): Emission? = try {
        withTimeoutOrNull(2_500) {
            if (input.size > 65_536) return@withTimeoutOrNull null
            val body = Json.parseToJsonElement(input.decodeToString(throwOnInvalidSequence = true)).jsonObject
            val event = (body["hook_event_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (event !in setOf("PreToolUse", "UserPromptSubmit")) return@withTimeoutOrNull null
            val session = (body["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@withTimeoutOrNull null
            if (!Regex("[A-Za-z0-9._-]{1,128}").matches(session)) return@withTimeoutOrNull null
            val claim = client.claimDirective(session, "EMITTED_TO_HOOK")
            val value = claim.directive ?: return@withTimeoutOrNull null
            if (claim.claimId == null || value.expiresAtMillis <= Clock.System.now().toEpochMilliseconds()) return@withTimeoutOrNull null
            Emission(session, claim, buildJsonObject { putJsonObject("hookSpecificOutput") {
                put("hookEventName", event)
                put("additionalContext", "TeamCode capacity guidance: ${value.message}")
            } }.toString())
        }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }

    /** Call only after writing the context. A lost receipt may repeat advice after the claim lease. */
    suspend fun acknowledge(emission: Emission) {
        try { withTimeoutOrNull(750) {
            client.acknowledgeDirective(emission.claim.directive!!.id, "EMITTED_TO_HOOK", emission.session, emission.claim.claimId)
        } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Never send diagnostics to model context. */ }
    }
}
