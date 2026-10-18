package me.nathanfallet.teamcode.infrastructure.protocols.stream

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

internal class MessagesStreamEncoder(model: String) : BaseStreamEncoder(model, "msg_") {
    override fun encode(e: StreamEvent): List<JsonObject> = when (e) {
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
    private fun anthropicUsage(u: TokenUsage) = buildJsonObject { put("input_tokens", u.input); put("output_tokens", u.output); put("cache_read_input_tokens", u.cacheRead); put("cache_creation_input_tokens", u.cacheCreation) }
}
