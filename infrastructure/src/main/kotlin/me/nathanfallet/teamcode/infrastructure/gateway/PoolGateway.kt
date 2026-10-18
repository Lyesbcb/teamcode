package me.nathanfallet.teamcode.infrastructure.gateway

import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.infrastructure.providers.ProviderTransport

/** Native subscription passthrough with durable admission, one bounded attempt and no replay after dispatch. */
class PoolGateway(private val admission: AdmissionStore, private val credentials: CredentialSource, private val transports: List<ProviderTransport>, private val modelPolicies: ModelPolicyStore) : GatewayService {
    override suspend fun status(): GatewaySnapshot = admission.statistics().let { GatewaySnapshot("subscription-pool", it.active, it.completed) }
    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
        val actor = request.principal ?: throw TeamFailure("authentication_required", 401, "Sign in to TeamCode.")
        actor.requireReady()
        val transport = transports.singleOrNull { it.protocol == request.protocol } ?: throw TeamFailure("unsupported_protocol", 400, "This protocol is not enabled.")
        val body = try { Json.parseToJsonElement(request.body.decodeToString()).jsonObject } catch (_: IllegalArgumentException) { throw TeamFailure("invalid_request", 400, "Invalid Messages request.") }
        val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: throw TeamFailure("invalid_model", 400, "A model is required.")
        if (!Regex("${if (request.protocol == WireProtocol.MESSAGES) "claude" else "gpt"}-[a-zA-Z0-9_.-]{1,113}").matches(model)) throw TeamFailure("unsupported_model", 400, "Use an explicit model identifier for this provider. Cross-provider routes are not enabled yet.")
        val outputConfig = body[if (request.protocol == WireProtocol.MESSAGES) "output_config" else "reasoning"] as? JsonObject
        val effortValue = outputConfig?.get("effort")
        val effort = (effortValue as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (effortValue != null && effort == null) throw TeamFailure("invalid_effort", 400, "Reasoning effort must be a string.")
        val thinking = body["thinking"] as? JsonObject
        val thinkingType = (thinking?.get("type") as? JsonPrimitive)?.contentOrNull
        if (body["thinking"] != null && thinkingType !in setOf("enabled", "disabled", "adaptive")) throw TeamFailure("invalid_thinking", 400, "Unsupported thinking configuration.")
        val thinkingBudget = if (thinkingType == "enabled") (thinking?.get("budget_tokens") as? JsonPrimitive)?.longOrNull
            ?: throw TeamFailure("invalid_thinking", 400, "An explicit thinking budget is required.") else null
        CheckModelPolicyUseCase().check(modelPolicies.get(actor.teamId), model, model, effort, thinkingBudget)
        val maximumOutput = if (request.protocol == WireProtocol.MESSAGES) (body["max_tokens"] as? JsonPrimitive)?.longOrNull
            else (body["max_output_tokens"] as? JsonPrimitive)?.longOrNull ?: 16_384
        if (maximumOutput == null || maximumOutput !in 1..65_536) throw TeamFailure("invalid_max_tokens", 400, "max_tokens must be between 1 and 65536.")
        if (listOf("thread", "previous_response_id", "conversation").any { body[it] != null && body[it] != JsonNull }) throw TeamFailure("thread_unsupported", 400, "Send the complete conversation; provider-managed message threads are not supported by this pool.")
        // Credits are team policy, not an asserted exchange rate for subscription percentages.
        val estimate = request.body.size.toLong() + 16_384 + maximumOutput * 5
        val reservation = admission.reserve(actor, estimate, model, transport.provider)
        val meter = SseUsageMeter()
        var knownCharge: Long? = null
        var dispatched = false
        var committed = false
        var completed = false
        try {
            val token = credentials.fresh(reservation.account)
            System.out.println("TeamCode inference dispatch request=${reservation.id} account=${reservation.account.id} provider=${transport.provider} model=$model")
            dispatched = true
            withTimeout(10 * 60_000L) {
                transport.send(reservation.account, token, body, request.headers) { response ->
                    val now = System.currentTimeMillis()
                    admission.observe(reservation.account.id, transport.quotas(reservation.account.id, response.headers, now))
                    val status = response.status.value
                    val encoding = response.headers["content-encoding"]?.takeIf { it in setOf("gzip", "br", "deflate", "zstd", "identity") } ?: "none"
                    System.out.println("TeamCode inference upstream request=${reservation.id} status=$status encoding=$encoding")
                    if (status !in 200..299) {
                        if (status in 400..499) knownCharge = 0
                        val retry = response.headers["retry-after"]?.toLongOrNull()?.coerceIn(1, 3600) ?: 60
                        if (status == 429 || status >= 500 || status == 401 || status == 403) admission.coolDown(reservation.account.id, now + retry * 1000, reconnect = status in setOf(401, 403))
                        val publicStatus = if (status in setOf(400, 413, 429)) status else 502
                        val publicError = if (status == 429) "The selected subscription is rate-limited. Retry after the indicated delay." else "The provider rejected this request. Check the model and account status."
                        val error = buildJsonObject { put("type", "error"); putJsonObject("error") { put("type", "upstream_error"); put("message", publicError) } }.toString().toByteArray()
                        committed = true
                        receive(GenerationHead(publicStatus, "application/json", mapOf("retry-after" to retry.toString(), "x-teamcode-request-id" to reservation.id)), flow { emit(error) })
                        return@send
                    }
                    val reportedType = response.headers["content-type"].orEmpty()
                    // The subscription Responses endpoint can omit Content-Type on a valid SSE stream.
                    // Its destination and stream=true contract are fixed; an explicit incompatible type still fails.
                    if (!reportedType.startsWith("text/event-stream") && !(reportedType.isEmpty() && transport.protocol == WireProtocol.RESPONSES)) throw TeamFailure("upstream_protocol", 502, "The provider did not return a stream.")
                    committed = true
                    receive(GenerationHead(status, "text/event-stream", mapOf("x-teamcode-request-id" to reservation.id)), flow {
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(8192)
                        var bytes = 0L
                        while (true) {
                            val count = channel.readAvailable(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            bytes += count
                            if (bytes > 64 * 1024 * 1024) throw TeamFailure("stream_limit", 502, "The response exceeded the stream limit.")
                            val chunk = buffer.copyOf(count)
                            meter.accept(chunk)
                            emit(chunk)
                        }
                    })
                    completed = meter.settledUnits != null
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (committed) throw error // Never write a replacement response or replay a committed stream.
            if (error is TeamFailure) throw error
            throw TeamFailure("upstream_unavailable", 502, "The provider request could not complete.")
        } finally {
            withContext(NonCancellable) {
                val charge = if (!dispatched) 0 else knownCharge ?: meter.settledUnits
                try {
                    withTimeout(5_000) { admission.settle(reservation, charge, completed) }
                    System.out.println("TeamCode inference settled request=${reservation.id} units=${charge ?: "unknown"} terminal=${meter.lastEvent} failure=${meter.failureCategory}")
                } catch (_: Exception) {
                    // The durable reservation remains charged; never mask cancellation or a stream failure.
                    System.err.println("TeamCode inference settlement pending request=${reservation.id}")
                }
            }
        }
    }
}
