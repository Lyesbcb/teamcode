package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.capacity.DirectiveService

internal fun Route.directiveRoutes(directives: DirectiveService) {
    get("/api/v1/directives/current") {
        val value = directives.current(call.principal)
        call.respond(DirectiveResponse(value?.view()))
    }
    post("/api/v1/directives/current") {
        val input = call.jsonBody<DirectiveScope>()
        call.respond(DirectiveResponse(directives.current(call.principal, input.sessionId)?.view()))
    }
    post("/api/v1/directives/claim") {
        val input = call.jsonBody<DirectiveClaimRequest>()
        val value = directives.claim(call.principal, input.sessionId, input.stage)
        call.respond(DirectiveClaimResponse(value?.directive?.view(), value?.claimId))
    }
    post("/api/v1/directives/receipt") {
        val input = call.jsonBody<DirectiveReceipt>()
        directives.acknowledge(call.principal, input.id, input.stage, input.sessionId, input.claimId)
        call.respond(SuccessResponse())
    }
    get("/dashboard/agent-guidance") {
        val value = directives.current(call.principal)
        val content = if (value == null) "<p>No fresh guidance yet. The controller is collecting observations.</p>" else """<div class="notice"><strong>${escape(value.advice.pace.name)}</strong><p>${escape(value.advice.message)}</p><p>Concurrency ceiling: ${value.advice.maxConcurrent}. Minimum admission interval: ${value.advice.minIntervalMillis} ms.</p><small>Expires ${java.time.Instant.ofEpochMilli(value.expiresAtMillis)}</small></div>"""
        call.respondText(page("Agent guidance", content + """<p>Run <code>teamcode run claude -- ...</code> to receive session guidance automatically, or <code>teamcode agents watch --session SESSION_ID</code> for scoped JSON updates. Manual bridges can use <code>teamcode agents hint --session SESSION_ID --format claude</code>. Delivery receipts record emission by the local bridge, not compliance by an agent. Capacity limits are enforced independently by the server.</p>"""), ContentType.Text.Html)
    }
}

private fun me.nathanfallet.teamcode.domain.capacity.AgentDirective.view() = DirectiveView(id, advice.pace.name, advice.maxConcurrent, advice.minIntervalMillis, advice.message, createdAtMillis, expiresAtMillis)
