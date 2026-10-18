package me.nathanfallet.teamcode.infrastructure.protocols.stream

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

internal class ResponsesStreamDecoder : BaseStreamDecoder() {
    private val blocks = mutableMapOf<String, Int>()
    private var next = 0
    override fun decode(value: JsonObject): List<StreamEvent> = buildList {
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
}
