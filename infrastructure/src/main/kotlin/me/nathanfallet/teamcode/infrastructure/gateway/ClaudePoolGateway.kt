package me.nathanfallet.teamcode.infrastructure.gateway

import io.ktor.client.statement.*
import io.ktor.utils.io.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport

/** Native Anthropic passthrough with durable admission, one bounded attempt and no replay after dispatch. */
class ClaudePoolGateway(private val admission: AdmissionStore, private val credentials: CredentialSource, private val transport: ClaudeTransport, private val modelPolicies: ModelPolicyStore) : GatewayService {
    private val active = AtomicInteger()
    private val completed = AtomicLong()
    override fun status() = GatewaySnapshot("claude-max-pool", active.get(), completed.get())
    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
        val actor = request.principal ?: throw TeamFailure("authentication_required", 401, "Sign in to TeamCode.")
        actor.requireReady()
        val body = try { Json.parseToJsonElement(request.body.decodeToString()).jsonObject } catch (_: IllegalArgumentException) { throw TeamFailure("invalid_request", 400, "Invalid Messages request.") }
        val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: throw TeamFailure("invalid_model", 400, "A model is required.")
        if (!Regex("claude-[a-zA-Z0-9_.-]{1,113}").matches(model)) throw TeamFailure("unsupported_model", 400, "Use an explicit Claude model identifier. Cross-provider routes are not enabled yet.")
        val outputConfig = body["output_config"] as? JsonObject
        val effortValue = outputConfig?.get("effort")
        val effort = (effortValue as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (effortValue != null && effort == null) throw TeamFailure("invalid_effort", 400, "Reasoning effort must be a string.")
        val thinking = body["thinking"] as? JsonObject
        val thinkingType = (thinking?.get("type") as? JsonPrimitive)?.contentOrNull
        if (body["thinking"] != null && thinkingType !in setOf("enabled", "disabled", "adaptive")) throw TeamFailure("invalid_thinking", 400, "Unsupported thinking configuration.")
        val thinkingBudget = if (thinkingType == "enabled") (thinking?.get("budget_tokens") as? JsonPrimitive)?.longOrNull
            ?: throw TeamFailure("invalid_thinking", 400, "An explicit thinking budget is required.") else null
        CheckModelPolicyUseCase().check(modelPolicies.get(actor.teamId), model, model, effort, thinkingBudget)
        val maximumOutput = (body["max_tokens"] as? JsonPrimitive)?.longOrNull
        if (maximumOutput == null || maximumOutput !in 1..65_536) throw TeamFailure("invalid_max_tokens", 400, "max_tokens must be between 1 and 65536.")
        if (body["thread"] != null) throw TeamFailure("thread_unsupported", 400, "Send the complete conversation; provider-managed message threads are not supported by this pool.")
        // Credits are team policy, not an asserted exchange rate for subscription percentages.
        val estimate = request.body.size.toLong() + 16_384 + maximumOutput * 5
        val reservation = admission.reserve(actor, estimate, model)
        val meter = SseUsageMeter()
        var knownCharge: Long? = null
        var dispatched = false
        var committed = false
        active.incrementAndGet()
        try {
            val token = credentials.fresh(reservation.account)
            System.out.println("TeamCode inference dispatch request=${reservation.id} account=${reservation.account.id} provider=anthropic model=$model")
            dispatched = true
            withTimeout(10 * 60_000L) {
                transport.messages(reservation.account, token, body, request.headers) { response ->
                    val now = System.currentTimeMillis()
                    admission.observe(reservation.account.id, ClaudeTransport.headerQuotas(reservation.account.id, response.headers, now))
                    val status = response.status.value
                    System.out.println("TeamCode inference upstream request=${reservation.id} status=$status")
                    if (status !in 200..299) {
                        if (status in 400..499) knownCharge = 0
                        val retry = response.headers["retry-after"]?.toLongOrNull()?.coerceIn(1, 3600) ?: 60
                        if (status == 429 || status >= 500 || status == 401 || status == 403) admission.coolDown(reservation.account.id, now + retry * 1000, reconnect = status in setOf(401, 403))
                        val publicStatus = if (status in setOf(400, 413, 429)) status else 502
                        val publicError = if (status == 429) "The selected subscription is rate-limited. Retry after the indicated delay." else "The provider rejected this request. Check the model and account status."
                        val error = buildJsonObject { put("type", "error"); putJsonObject("error") { put("type", "upstream_error"); put("message", publicError) } }.toString().toByteArray()
                        committed = true
                        receive(GenerationHead(publicStatus, "application/json", mapOf("retry-after" to retry.toString(), "x-teamcode-request-id" to reservation.id)), flow { emit(error) })
                        return@messages
                    }
                    if (!response.headers["content-type"].orEmpty().startsWith("text/event-stream")) throw TeamFailure("upstream_protocol", 502, "The provider did not return a stream.")
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
                    completed.incrementAndGet()
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (committed) throw error // Never write a replacement response or replay a committed stream.
            if (error is TeamFailure) throw error
            throw TeamFailure("upstream_unavailable", 502, "The provider request could not complete.")
        } finally {
            active.decrementAndGet()
            withContext(NonCancellable) {
                val charge = if (!dispatched) 0 else knownCharge ?: meter.settledUnits
                try {
                    withTimeout(5_000) { admission.settle(reservation, charge) }
                    System.out.println("TeamCode inference settled request=${reservation.id} units=${charge ?: "unknown"}")
                } catch (_: Exception) {
                    // The durable reservation remains charged; never mask cancellation or a stream failure.
                    System.err.println("TeamCode inference settlement pending request=${reservation.id}")
                }
            }
        }
    }
}
