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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.*
import me.nathanfallet.teamcode.domain.gateway.*

private const val MAX_BODY_BYTES = 2 * 1024 * 1024

fun Application.configureGateway(gateway: GatewayService, accessToken: String) {
    require(accessToken.length >= 32) { "The development access token must contain at least 32 characters." }
    val expected = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray())
    install(io.ktor.server.resources.Resources)
    install(ContentNegotiation) { json(Serialization.json) }
    install(StatusPages) {
        exception<CancellationException> { _, error -> throw error }
        exception<InvalidRequest> { call, error -> call.respond(HttpStatusCode.BadRequest, ApiError(ErrorDetail("invalid_request", error.message ?: "Invalid request."))) }
        exception<PayloadTooLarge> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, ApiError(ErrorDetail("payload_too_large", "Request exceeds the configured limit."))) }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path() != "/health") {
            val presented = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ") ?: ""
            val actual = MessageDigest.getInstance("SHA-256").digest(presented.toByteArray())
            if (!MessageDigest.isEqual(expected, actual)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorDetail("authentication_required", "A valid TeamCode access token is required.")))
                finish()
            }
        }
    }
    routing {
        get("/health") { call.respondText("OK") }
        get<CapabilitiesResource> {
            call.respond(Capabilities(serverVersion = "0.1.0-SNAPSHOT", mode = gateway.status().mode,
                features = listOf("status", "messages.fixture.streaming")))
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
            if (value["stream"] != JsonPrimitive(true)) throw InvalidRequest("The development fixture requires stream=true.")
            val model = value["model"] as? JsonPrimitive
            if (model?.isString != true || model.content.isBlank()) throw InvalidRequest("A model name is required.")
            if (value["messages"] !is JsonArray) throw InvalidRequest("A messages array is required.")
            val headers = listOf("anthropic-version", "anthropic-beta", "x-claude-code-session-id")
                .mapNotNull { name -> call.request.header(name)?.let { name to it } }.toMap()
            gateway.forward(GenerationRequest(bytes, headers)) { head, chunks ->
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
