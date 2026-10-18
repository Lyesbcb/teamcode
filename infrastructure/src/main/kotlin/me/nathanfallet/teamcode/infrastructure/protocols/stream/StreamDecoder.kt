package me.nathanfallet.teamcode.infrastructure.protocols.stream

import java.util.UUID
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

interface StreamDecoder {
    val finished: Boolean
    fun accept(value: JsonObject): List<StreamEvent>
}

internal abstract class BaseStreamDecoder : StreamDecoder {
    protected var started = false
    final override var finished = false
        protected set
    protected var usage = TokenUsage()
    protected var inputUsageSeen = false
    protected var outputUsageSeen = false
    protected val open = mutableSetOf<Int>()
    protected val tools = mutableSetOf<Int>()
    final override fun accept(value: JsonObject): List<StreamEvent> = if (finished) emptyList() else decode(value)
    protected abstract fun decode(value: JsonObject): List<StreamEvent>
    protected fun begin(id: String?, result: MutableList<StreamEvent>) {
        if (!started) { result += StreamEvent.Start(id ?: UUID.randomUUID().toString()); started = true }
    }
    protected fun record(u: JsonObject?, responses: Boolean) {
        if (u == null) return
        fun count(name: String, previous: Long): Long = u[name]?.let { (it as? JsonPrimitive)?.longOrNull?.takeIf { n -> n in 0..10_000_000 } ?: unsupportedStream() } ?: previous
        inputUsageSeen = inputUsageSeen || u["input_tokens"] != null
        outputUsageSeen = outputUsageSeen || u["output_tokens"] != null
        val totalInput = count("input_tokens", usage.input)
        val cached = if (responses) ((u["input_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.longOrNull ?: 0 else count("cache_read_input_tokens", usage.cacheRead)
        if (cached !in 0..10_000_000 || responses && cached > totalInput) unsupportedStream()
        usage = TokenUsage(if (responses) totalInput - cached else totalInput, count("output_tokens", usage.output), cached, if (responses) 0 else count("cache_creation_input_tokens", usage.cacheCreation))
    }
}
