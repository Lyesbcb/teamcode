package me.nathanfallet.teamcode.infrastructure.protocols

import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

class StreamTranslation {
    fun translate(source: Flow<ByteArray>, from: WireProtocol, to: WireProtocol, model: String): Flow<ByteArray> {
        require(from != to)
        return flow {
            val decoder = EventDecoder(from)
            val encoder = EventEncoder(to, model)
            val frames = SseFrames()
            // catch observes only upstream/translation failures, never errors thrown by the downstream collector.
            flow {
                source.collect { bytes -> frames.accept(bytes).forEach { value -> decoder.accept(value).forEach { event ->
                    encoder.accept(event).forEach { emit(it) }
                } } }
                if (!decoder.finished) unsupportedStream()
            }.catch { error ->
                if (error is CancellationException) throw error
                encoder.accept(StreamEvent.Failure).forEach { emit(it) }
                throw error
            }.collect { emit(it) }
        }
    }
}

/** Handles split UTF-8 and multiline SSE data; bounds incomplete lines and complete events. */
internal class SseFrames {
    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    fun accept(bytes: ByteArray): List<JsonObject> = buildList {
        bytes.forEach { b ->
            if (b == 10.toByte()) {
                val text = line.toString(Charsets.UTF_8).trimEnd('\r')
                line.reset()
                if (text.isEmpty()) {
                    val payload = data.toString().trim(); data.clear()
                    if (payload.isNotEmpty() && payload != "[DONE]") add(parse(payload))
                } else if (text.startsWith("data:")) {
                    data.append(text.removePrefix("data:").removePrefix(" ")).append('\n')
                    if (data.length > LIMIT) unsupportedStream()
                }
            } else {
                if (line.size() >= LIMIT) unsupportedStream()
                line.write(b.toInt())
            }
        }
    }
    private fun parse(value: String) = try { Json.parseToJsonElement(value) as? JsonObject ?: unsupportedStream() } catch (_: IllegalArgumentException) { unsupportedStream() }
    companion object { const val LIMIT = 1_048_576 }
}

private class EventDecoder(private val protocol: WireProtocol) {
    private var started = false
    var finished = false
        private set
    private var usage = TokenUsage()
    private var stop = StopReason.END_TURN
    private var inputUsageSeen = false
    private var outputUsageSeen = false
    private var messageDeltaSeen = false
    private val blocks = mutableMapOf<String, Int>()
    private val open = mutableSetOf<Int>()
    private val tools = mutableSetOf<Int>()
    private var next = 0
    fun accept(value: JsonObject): List<StreamEvent> {
        if (finished) return emptyList()
        return if (protocol == WireProtocol.MESSAGES) anthropic(value) else responses(value)
    }
    private fun begin(id: String?, result: MutableList<StreamEvent>) {
        if (!started) { result += StreamEvent.Start(id ?: UUID.randomUUID().toString()); started = true }
    }
    private fun anthropic(value: JsonObject): List<StreamEvent> = buildList {
        when (value.str("type")) {
            "message_start" -> {
                val message = value["message"].obj(); begin(message.str("id"), this)
                record(message["usage"] as? JsonObject, false)
            }
            "content_block_start" -> {
                begin(null, this)
                val index = value.index("index"); val block = value["content_block"].obj()
                if (!open.add(index) || open.size > 256) unsupportedStream()
                when (block.str("type")) {
                    "text" -> { add(StreamEvent.TextStart(index)); block.str("text")?.takeIf { it.isNotEmpty() }?.let { add(StreamEvent.Delta(index, it)) } }
                    "tool_use" -> {
                        tools += index; add(StreamEvent.ToolStart(index, block.required("id"), block.required("name")))
                        block["input"]?.takeIf { it is JsonObject && it.isNotEmpty() }?.let { add(StreamEvent.Delta(index, it.toString())) }
                    }
                    else -> unsupportedStream()
                }
            }
            "content_block_delta" -> {
                val index = value.index("index"); if (index !in open) unsupportedStream()
                val delta = value["delta"].obj()
                val text = when (delta.str("type")) { "text_delta" -> delta.required("text"); "input_json_delta" -> delta.required("partial_json"); else -> unsupportedStream() }
                add(StreamEvent.Delta(index, text))
            }
            "content_block_stop" -> { val index = value.index("index"); if (!open.remove(index)) unsupportedStream(); add(StreamEvent.End(index)) }
            "message_delta" -> {
                messageDeltaSeen = true
                record(value["usage"] as? JsonObject, false)
                stop = when ((value["delta"] as? JsonObject)?.str("stop_reason")) {
                    null, "end_turn", "stop_sequence" -> StopReason.END_TURN
                    "tool_use" -> StopReason.TOOL_USE
                    "max_tokens" -> StopReason.MAX_TOKENS
                    else -> unsupportedStream()
                }
            }
            "message_stop" -> {
                if (!started || open.isNotEmpty() || !messageDeltaSeen || !inputUsageSeen || !outputUsageSeen) unsupportedStream()
                add(StreamEvent.Finish(stop, usage)); finished = true
            }
            "ping" -> Unit
            "error" -> { add(StreamEvent.Failure); finished = true }
            else -> unsupportedStream()
        }
    }
    private fun responses(value: JsonObject): List<StreamEvent> = buildList {
        val type = value.str("type")
        when (type) {
            "response.created", "response.in_progress" -> begin((value["response"] as? JsonObject)?.str("id"), this)
            "response.output_item.added" -> {
                begin(null, this)
                val item = value["item"].obj()
                when (item.str("type")) {
                    "function_call" -> {
                        val index = allocate("tool:${value.index("output_index")}")
                        if (!open.add(index)) unsupportedStream()
                        tools += index; add(StreamEvent.ToolStart(index, item.required("call_id"), item.required("name")))
                    }
                    "message" -> Unit
                    "reasoning" -> checkReasoning(item)
                    else -> unsupportedStream()
                }
            }
            "response.content_part.added" -> {
                val part = value["part"].obj()
                checkText(part)
                textStart(value, this)
            }
            "response.output_text.delta" -> {
                val index = textStart(value, this)
                add(StreamEvent.Delta(index, value.required("delta")))
            }
            "response.function_call_arguments.delta" -> {
                val index = blocks["tool:${value.index("output_index")}"] ?: unsupportedStream()
                if (index !in open) unsupportedStream()
                add(StreamEvent.Delta(index, value.required("delta")))
            }
            "response.content_part.done" -> {
                checkText(value["part"].obj())
                val index = blocks[textKey(value)] ?: unsupportedStream()
                if (open.remove(index)) add(StreamEvent.End(index))
            }
            "response.output_item.done" -> {
                val item = value["item"].obj()
                when (item.str("type")) {
                    "function_call" -> { val index = blocks["tool:${value.index("output_index")}"] ?: unsupportedStream(); if (open.remove(index)) add(StreamEvent.End(index)) }
                    "message" -> (item["content"] as? JsonArray)?.forEach { checkText(it.obj()) }
                    "reasoning" -> checkReasoning(item)
                    else -> unsupportedStream()
                }
            }
            "response.output_text.done", "response.function_call_arguments.done" -> Unit // Delta content was already emitted.
            "response.completed", "response.incomplete" -> {
                val response = value["response"].obj()
                (response["output"] as? JsonArray)?.forEach { element ->
                    val item = element.obj()
                    when (item.str("type")) {
                        "reasoning" -> checkReasoning(item)
                        "message" -> (item["content"] as? JsonArray)?.forEach { checkText(it.obj()) }
                        "function_call" -> Unit
                        else -> unsupportedStream()
                    }
                }
                record(response["usage"] as? JsonObject, true)
                if (!started || !inputUsageSeen || !outputUsageSeen) unsupportedStream("usage")
                open.toList().forEach { add(StreamEvent.End(it)) }; open.clear()
                val reason = if (type == "response.incomplete") {
                    if ((response["incomplete_details"] as? JsonObject)?.str("reason") != "max_output_tokens") unsupportedStream()
                    StopReason.MAX_TOKENS
                } else if (tools.isNotEmpty()) StopReason.TOOL_USE else StopReason.END_TURN
                add(StreamEvent.Finish(reason, usage)); finished = true
            }
            // Subscription transport/model-verification metadata has no Messages content-block equivalent.
            // It does not complete a turn; continue waiting for the real terminal event and usage.
            "response.metadata", "codex.response.metadata" -> Unit
            "response.failed", "error" -> { add(StreamEvent.Failure); finished = true }
            else -> unsupportedStream("event_" + (type?.takeIf { Regex("response\\.[a-z_.]{1,80}").matches(it) } ?: "unknown"))
        }
    }
    private fun checkReasoning(item: JsonObject) {
        if ((item["summary"] as? JsonArray)?.isNotEmpty() == true || item["encrypted_content"]?.let { it != JsonNull } == true) unsupportedStream("reasoning")
    }
    private fun checkText(part: JsonObject) {
        if (part.str("type") != "output_text" || (part["annotations"] as? JsonArray)?.isNotEmpty() == true ||
            (part["logprobs"] as? JsonArray)?.isNotEmpty() == true) unsupportedStream("content")
    }
    private fun allocate(key: String): Int = blocks.getOrPut(key) { if (next >= 256) unsupportedStream(); next++ }
    private fun textKey(v: JsonObject) = "text:${v.index("output_index")}:${v.index("content_index")}"
    private fun textStart(v: JsonObject, events: MutableList<StreamEvent>): Int {
        begin(null, events)
        val index = allocate(textKey(v))
        if (open.add(index)) events += StreamEvent.TextStart(index)
        return index
    }
    private fun record(u: JsonObject?, responses: Boolean) {
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

private class EventEncoder(private val protocol: WireProtocol, private val model: String) {
    private val id = (if (protocol == WireProtocol.MESSAGES) "msg_" else "resp_") + UUID.randomUUID().toString().replace("-", "")
    private var sequence = 0
    private var terminated = false
    private var bytes = 0L
    private val blocks = linkedMapOf<Int, Block>()
    private data class Block(val index: Int, val callId: String? = null, val name: String? = null, val content: StringBuilder = StringBuilder(), var closed: Boolean = false)
    fun accept(event: StreamEvent): List<ByteArray> {
        if (terminated) return emptyList()
        val values = if (protocol == WireProtocol.MESSAGES) messages(event) else responses(event)
        if (event is StreamEvent.Finish || event == StreamEvent.Failure) terminated = true
        return values.map { value -> "event: ${value.required("type")}\ndata: $value\n\n".toByteArray() }
    }
    private fun event(type: String, fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("type", type); if (protocol == WireProtocol.RESPONSES) put("sequence_number", sequence++); fields() }
    private fun messages(e: StreamEvent): List<JsonObject> = when (e) {
        is StreamEvent.Start -> listOf(event("message_start") { putJsonObject("message") {
            put("id", id); put("type", "message"); put("role", "assistant"); put("model", model); put("content", JsonArray(emptyList())); put("stop_reason", JsonNull); put("stop_sequence", JsonNull); put("usage", anthropicUsage(TokenUsage()))
        } })
        is StreamEvent.TextStart -> { addBlock(e.index); listOf(event("content_block_start") { put("index", e.index); putJsonObject("content_block") { put("type", "text"); put("text", "") } }) }
        is StreamEvent.ToolStart -> { addBlock(e.index, e.id, e.name); listOf(event("content_block_start") { put("index", e.index); putJsonObject("content_block") { put("type", "tool_use"); put("id", e.id); put("name", e.name); put("input", JsonObject(emptyMap())) } }) }
        is StreamEvent.Delta -> { val b = append(e); listOf(event("content_block_delta") { put("index", e.index); putJsonObject("delta") { put("type", if (b.callId == null) "text_delta" else "input_json_delta"); put(if (b.callId == null) "text" else "partial_json", e.value) } }) }
        is StreamEvent.End -> { close(e.index); listOf(event("content_block_stop") { put("index", e.index) }) }
        is StreamEvent.Finish -> listOf(event("message_delta") { putJsonObject("delta") { put("stop_reason", when (e.reason) { StopReason.END_TURN -> "end_turn"; StopReason.TOOL_USE -> "tool_use"; StopReason.MAX_TOKENS -> "max_tokens" }); put("stop_sequence", JsonNull) }; put("usage", anthropicUsage(e.usage)) }, event("message_stop"))
        StreamEvent.Failure -> listOf(event("error") { putJsonObject("error") { put("type", "api_error"); put("message", "The translated provider stream could not complete. No replay was attempted.") } })
    }
    private fun responses(e: StreamEvent): List<JsonObject> = when (e) {
        is StreamEvent.Start -> listOf(event("response.created") { put("response", response("in_progress")) }, event("response.in_progress") { put("response", response("in_progress")) })
        is StreamEvent.TextStart -> {
            val b = addBlock(e.index)
            listOf(event("response.output_item.added") { put("output_index", b.index); put("item", item(b, false)) }, event("response.content_part.added") { coordinates(b); put("part", textPart("")) })
        }
        is StreamEvent.ToolStart -> { val b = addBlock(e.index, e.id, e.name); listOf(event("response.output_item.added") { put("output_index", b.index); put("item", item(b, false)) }) }
        is StreamEvent.Delta -> { val b = append(e); listOf(event(if (b.callId == null) "response.output_text.delta" else "response.function_call_arguments.delta") { coordinates(b, b.callId == null); put("delta", e.value) }) }
        is StreamEvent.End -> {
            val b = close(e.index)
            buildList {
                if (b.callId == null) {
                    add(event("response.output_text.done") { coordinates(b); put("text", b.content.toString()) })
                    add(event("response.content_part.done") { coordinates(b); put("part", textPart(b.content.toString())) })
                } else add(event("response.function_call_arguments.done") { coordinates(b, false); put("arguments", b.content.toString()) })
                add(event("response.output_item.done") { put("output_index", b.index); put("item", item(b, true)) })
            }
        }
        is StreamEvent.Finish -> listOf(event(if (e.reason == StopReason.MAX_TOKENS) "response.incomplete" else "response.completed") { put("response", response(if (e.reason == StopReason.MAX_TOKENS) "incomplete" else "completed", e.usage)) })
        StreamEvent.Failure -> listOf(event("response.failed") { put("response", response("failed")) })
    }
    private fun addBlock(index: Int, call: String? = null, name: String? = null): Block {
        if (index in blocks || blocks.size >= 256) unsupportedStream()
        return Block(blocks.size, call, name).also { blocks[index] = it }
    }
    private fun append(e: StreamEvent.Delta): Block {
        val b = blocks[e.index]?.takeIf { !it.closed } ?: unsupportedStream()
        bytes += e.value.toByteArray().size
        if (bytes > 8 * 1024 * 1024) unsupportedStream()
        b.content.append(e.value); return b
    }
    private fun close(index: Int): Block = (blocks[index]?.takeIf { !it.closed } ?: unsupportedStream()).also {
        if (it.callId != null && it.content.isEmpty()) it.content.append("{}")
        if (it.callId != null) try { if (Json.parseToJsonElement(it.content.toString()) !is JsonObject) unsupportedStream() } catch (_: IllegalArgumentException) { unsupportedStream() }
        it.closed = true
    }
    private fun JsonObjectBuilder.coordinates(b: Block, content: Boolean = true) { put("output_index", b.index); put("item_id", "${id}_${b.index}"); if (content) put("content_index", 0) }
    private fun textPart(text: String) = buildJsonObject { put("type", "output_text"); put("text", text); put("annotations", JsonArray(emptyList())); put("logprobs", JsonArray(emptyList())) }
    private fun item(b: Block, done: Boolean) = buildJsonObject {
        put("id", "${id}_${b.index}"); put("status", if (done) "completed" else "in_progress")
        if (b.callId != null) { put("type", "function_call"); put("call_id", b.callId); put("name", b.name); put("arguments", if (done) b.content.toString() else "") }
        else { put("type", "message"); put("role", "assistant"); put("content", if (done) JsonArray(listOf(textPart(b.content.toString()))) else JsonArray(emptyList())) }
    }
    private fun response(status: String, usage: TokenUsage? = null) = buildJsonObject {
        put("id", id); put("object", "response"); put("created_at", System.currentTimeMillis() / 1000); put("model", model); put("status", status)
        put("output", JsonArray(blocks.values.filter { it.closed }.map { item(it, true) }))
        put("error", if (status == "failed") buildJsonObject { put("code", "translation_failed"); put("message", "The translated provider stream could not complete. No replay was attempted.") } else JsonNull)
        put("incomplete_details", if (status == "incomplete") buildJsonObject { put("reason", "max_output_tokens") } else JsonNull)
        put("usage", usage?.let { u -> buildJsonObject {
            val total = u.input + u.cacheRead + u.cacheCreation
            put("input_tokens", total); putJsonObject("input_tokens_details") { put("cached_tokens", u.cacheRead) }; put("output_tokens", u.output); put("total_tokens", total + u.output)
        } } ?: JsonNull)
    }
    private fun anthropicUsage(u: TokenUsage) = buildJsonObject { put("input_tokens", u.input); put("output_tokens", u.output); put("cache_read_input_tokens", u.cacheRead); put("cache_creation_input_tokens", u.cacheCreation) }
}
private fun JsonElement?.obj() = this as? JsonObject ?: unsupportedStream()
private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.required(key: String) = str(key) ?: unsupportedStream()
private fun JsonObject.index(key: String) = (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..255 } ?: unsupportedStream()
private fun unsupportedStream(category: String = "invalid"): Nothing = throw TeamFailure("translation_stream_$category", 502, "The provider stream contains unsupported or invalid translation semantics.")
