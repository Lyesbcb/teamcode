package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.*
import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.accounts.AccountManagement
import me.nathanfallet.teamcode.domain.capacity.AdmissionStore
import me.nathanfallet.teamcode.domain.capacity.DirectiveService
import me.nathanfallet.teamcode.domain.policy.ModelPolicyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.*
import me.nathanfallet.teamcode.domain.gateway.*

private const val MAX_BODY_BYTES = 2 * 1024 * 1024

fun Application.configureGateway(gateway: GatewayService, accessToken: String = "", team: TeamService? = null, publicOrigin: String = "http://localhost:8080", accounts: AccountManagement? = null, admission: AdmissionStore? = null, directives: DirectiveService? = null, modelPolicies: ModelPolicyStore? = null) {
    require(team != null || accessToken.length >= 32) { "The development access token must contain at least 32 characters." }
    val expected = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray())
    install(io.ktor.server.resources.Resources)
    install(ContentNegotiation) { json(Serialization.json) }
    install(StatusPages) {
        exception<TeamFailure> { call, error ->
            if (error.status == 429) call.response.header(HttpHeaders.RetryAfter, "60")
            if (call.request.path().startsWith("/dashboard")) call.respondText(page("Request failed", "<p>${escape(error.message ?: "Request failed.")}</p><a href='/dashboard'>Back to dashboard</a>"), ContentType.Text.Html, HttpStatusCode.fromValue(error.status))
            else call.respond(HttpStatusCode.fromValue(error.status), ApiError(ErrorDetail(error.code, error.message ?: "Request failed.")))
        }
        exception<CancellationException> { _, error -> throw error }
        exception<InvalidRequest> { call, error -> call.respond(HttpStatusCode.BadRequest, ApiError(ErrorDetail("invalid_request", error.message ?: "Invalid request."))) }
        exception<PayloadTooLarge> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, ApiError(ErrorDetail("payload_too_large", "Request exceeds the configured limit."))) }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        val path = call.request.path()
        if (team != null) {
            if (path.startsWith("/dashboard")) call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            if (path !in setOf("/health", "/", "/dashboard/login", "/api/v1/auth/login")) {
                val credential = if (path.startsWith("/dashboard")) call.request.cookies["teamcode_session"]
                    else call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
                val principal = credential?.let { team.authenticate(it) }
                if (principal == null) {
                    if (path.startsWith("/dashboard")) call.respondRedirect("/dashboard/login")
                    else call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorDetail("authentication_required", "Sign in to TeamCode.")))
                    finish()
                } else {
                    call.attributes.put(PRINCIPAL, principal)
                    if (path !in setOf("/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/logout", "/dashboard/password", "/dashboard/logout")) {
                        if (principal.member.passwordChangeRequired && path.startsWith("/dashboard")) {
                            call.respondRedirect("/dashboard/password"); finish()
                        } else principal.requireReady()
                    }
                }
            }
        } else if (path != "/health") {
            val presented = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ") ?: ""
            val actual = MessageDigest.getInstance("SHA-256").digest(presented.toByteArray())
            if (!MessageDigest.isEqual(expected, actual)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorDetail("authentication_required", "A valid TeamCode access token is required.")))
                finish()
            }
        }
    }
    routing {
        if (team != null) { teamRoutes(team); dashboardRoutes(team, publicOrigin) }
        if (accounts != null) accountRoutes(accounts, publicOrigin)
        if (admission != null && team != null) capacityRoutes(admission, team, publicOrigin)
        if (directives != null) directiveRoutes(directives)
        if (modelPolicies != null) modelPolicyRoutes(modelPolicies, publicOrigin)
        get("/health") { call.respondText("OK") }
        get<CapabilitiesResource> {
            call.respond(Capabilities(serverVersion = "0.1.0-SNAPSHOT", mode = gateway.status().mode,
                features = listOf("status", if (gateway.status().mode == "fixture") "messages.fixture.streaming" else "messages.claude-max.streaming") + if (team != null) listOf("team.auth", "team.members", "dashboard") else emptyList()))
        }
        get<StatusResource> {
            val status = gateway.status()
            call.respond(GatewayStatus(status.mode, status.active, status.completed))
        }
        post("/v1/messages") {
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) throw InvalidRequest("A JSON request body is required.")
            val bytes = call.receiveChannel().readBounded(MAX_BODY_BYTES)
            val value = try { Serialization.json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
                catch (_: IllegalArgumentException) { null } ?: throw InvalidRequest("A JSON object is required.")
            if (value["stream"] != JsonPrimitive(true)) throw InvalidRequest("This endpoint requires stream=true.")
            val model = value["model"] as? JsonPrimitive
            if (model?.isString != true || model.content.isBlank()) throw InvalidRequest("A model name is required.")
            if (value["messages"] !is JsonArray) throw InvalidRequest("A messages array is required.")
            val headers = listOf("anthropic-version", "anthropic-beta", "x-claude-code-session-id", "user-agent")
                .mapNotNull { name -> call.request.header(name)?.let { name to it } }.toMap()
            gateway.forward(GenerationRequest(bytes, headers, if (team != null) call.principal else null)) { head, chunks ->
                head.headers.filterKeys { it.lowercase() in setOf("retry-after", "x-teamcode-request-id", "x-teamcode-account-id") }.forEach { (name, value) -> call.response.header(name, value) }
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Accel-Buffering", "no")
                val consumed = CompletableDeferred<Unit>()
                call.respondBytesWriter(ContentType.parse(head.contentType), HttpStatusCode.fromValue(head.status)) {
                    try {
                        chunks.collect { chunk -> writeFully(chunk); flush() }
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

suspend fun ByteReadChannel.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = readAvailable(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        if (count == 0) continue
        if (output.size() + count > limit) throw PayloadTooLarge()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private class InvalidRequest(message: String) : Exception(message)
private class PayloadTooLarge : Exception()
