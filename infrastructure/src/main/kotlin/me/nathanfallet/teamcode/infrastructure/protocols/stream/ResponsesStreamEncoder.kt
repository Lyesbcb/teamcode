package me.nathanfallet.teamcode.infrastructure.protocols.stream

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

internal class ResponsesStreamEncoder(model: String) : BaseStreamEncoder(model, "resp_") {
    private var sequence = 0
    override fun event(type: String, fields: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", type); put("sequence_number", sequence++); fields()
    }
    override fun encode(e: StreamEvent): List<JsonObject> = when (e) {
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
}
