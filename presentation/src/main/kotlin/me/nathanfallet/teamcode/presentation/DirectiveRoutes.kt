package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.capacity.DirectiveService

internal fun Route.directiveRoutes(directives: DirectiveService) {
    get("/api/v1/directives/current") {
        val value = directives.current(call.principal)
        call.respond(DirectiveResponse(value?.let { DirectiveView(it.id, it.advice.pace.name, it.advice.maxConcurrent, it.advice.minIntervalMillis, it.advice.message, it.createdAtMillis, it.expiresAtMillis) }))
    }
    post("/api/v1/directives/receipt") {
        val input = call.jsonBody<DirectiveReceipt>()
        directives.acknowledge(call.principal, input.id, input.stage)
        call.respond(SuccessResponse())
    }
    get("/dashboard/agent-guidance") {
        val value = directives.current(call.principal)
        val content = if (value == null) "<p>No fresh guidance yet. The controller is collecting observations.</p>" else """<div class="notice"><strong>${escape(value.advice.pace.name)}</strong><p>${escape(value.advice.message)}</p><p>Concurrency ceiling: ${value.advice.maxConcurrent}. Minimum admission interval: ${value.advice.minIntervalMillis} ms.</p><small>Expires ${java.time.Instant.ofEpochMilli(value.expiresAtMillis)}</small></div>"""
        call.respondText(page("Agent guidance", content + """<p>Run <code>teamcode agents watch</code> for a stream of guidance updates, or use <code>teamcode agents hint --format claude</code> in a Claude Code PreToolUse hook. Delivery receipts mean the CLI emitted the guidance; they do not claim an agent followed it. Capacity limits are enforced independently by the server.</p>"""), ContentType.Text.Html)
    }
}
