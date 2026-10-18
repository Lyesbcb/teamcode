package me.nathanfallet.teamcode.infrastructure.protocols.stream

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

internal class MessagesStreamDecoder : BaseStreamDecoder() {
    private var stop = StopReason.END_TURN
    private var messageDeltaSeen = false
    override fun decode(value: JsonObject): List<StreamEvent> = buildList {
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
}
