package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.infrastructure.protocols.stream.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*


class ResponsesProtocolAdapter : ProtocolAdapter {
    override fun decoder(): StreamDecoder = ResponsesStreamDecoder()
    override fun encoder(model: String, tools: List<ToolDefinition>?): StreamEncoder = ResponsesStreamEncoder(model, tools)
    override val protocol = WireProtocol.RESPONSES
    override fun decode(body: JsonObject): Conversation {
        fields(body, setOf("model", "stream", "store", "instructions", "input", "tools", "tool_choice", "parallel_tool_calls", "reasoning", "max_output_tokens", "temperature", "top_p", "metadata", "include", "text", "prompt_cache_key", "prompt_cache_retention", "service_tier", "safety_identifier", "client_metadata"))
        if (body["store"] == JsonPrimitive(true)) unsupported("Provider-side stored state cannot be translated.")
        val include = (body["include"] as? JsonArray).orEmpty()
        if (include.any { it.primitiveText() != "reasoning.encrypted_content" }) unsupported("This response include option is unsupported by the target protocol.")
        // An encrypted-content request is advisory for stateless reasoning replay. Opaque items in input still fail below.
        val reasoning = body["reasoning"] as? JsonObject
        reasoning?.let { fields(it, setOf("effort", "summary", "context")) }
        if (reasoning?.optionalText("summary") !in setOf(null, "auto", "none")) unsupported("Explicit reasoning summary formats cannot be translated.")
        if (reasoning?.optionalText("context") != null &&
            (reasoning.optionalText("effort") != "none" || reasoning.optionalText("context") != "all_turns")) unsupported("This reasoning-context mode cannot be preserved across providers.")
        val text = body["text"] as? JsonObject
        text?.let {
            fields(it, setOf("format", "verbosity"))
            val format = it["format"] as? JsonObject
            if (format != null && (format.size != 1 || format.text("type") != "text")) unsupported("Structured output translation is not enabled.")
        }
        val verbosity = text?.optionalText("verbosity")
        if (verbosity !in setOf(null, "low", "medium", "high")) unsupported("Unsupported response verbosity.")
        val declaredTools = ResponsesTools()
        (body["tools"] as? JsonArray)?.let { declaredTools.add(it) }
        if (body.optionalText("service_tier") !in setOf(null, "auto", "default")) unsupported("This service tier cannot be preserved across providers.")
        val instructions = mutableListOf<String>()
        body.optionalText("instructions")?.let(instructions::add)
        val turns = mutableListOf<Turn>()
        val input = body["input"]
        if (input is JsonPrimitive && input.isString) turns += Turn("user", listOf(Part.Text(input.content)))
        else (input as? JsonArray ?: unsupported("Expected a Responses input array.")).forEach { item ->
            val value = item.obj()
            when (value.optionalText("type") ?: "message") {
                "message" -> {
                    fields(value, setOf("type", "role", "content", "id", "status", "phase"))
                    val role = value.text("role")
                    val parts = responseParts(value["content"])
                    when (role) {
                        "system", "developer" -> {
                            if (parts.any { it !is Part.Text }) unsupported("System instructions must be text.")
                            instructions += parts.filterIsInstance<Part.Text>().map { it.text }
                        }
                        "user", "assistant" -> turns += Turn(role, parts)
                        else -> unsupported("Unsupported input role.")
                    }
                }
                "additional_tools" -> {
                    fields(value, setOf("type", "id", "role", "tools"))
                    if (value.text("role") != "developer") unsupported("Additional tools must be developer declarations.")
                    declaredTools.add(value.array("tools"))
                }
                "function_call", "custom_tool_call" -> {
                    fields(value, setOf("type", "id", "call_id", "name", "arguments", "input", "status", "namespace"))
                    val custom = value.text("type") == "custom_tool_call"
                    if (value[if (custom) "arguments" else "input"] != null) unsupported("Tool call mixes incompatible input formats.")
                    turns += Turn("assistant", listOf(Part.ToolCall(value.text("call_id"), value.text("name"), value.text(if (custom) "input" else "arguments"), value.optionalText("namespace"), if (custom) ToolInputFormat.TEXT else ToolInputFormat.JSON)))
                }
                "function_call_output", "custom_tool_call_output" -> {
                    fields(value, setOf("type", "id", "call_id", "output", "status"))
                    turns += Turn("user", listOf(Part.ToolResult(value.text("call_id"), responseParts(value["output"]), inputFormat = if (value.text("type") == "custom_tool_call_output") ToolInputFormat.TEXT else ToolInputFormat.JSON)))
                }
                else -> unsupported("Opaque reasoning, built-in tools and provider state cannot be translated safely.")
            }
        }
        val tools = declaredTools.values()
        val choice = when (val selected = body["tool_choice"]) {
            null -> ToolChoice.Auto
            is JsonPrimitive -> when (selected.content) { "auto" -> ToolChoice.Auto; "none" -> ToolChoice.None; "required" -> ToolChoice.Required; else -> unsupported("Unsupported tool choice.") }
            is JsonObject -> { fields(selected, setOf("type", "name", "namespace")); if (selected.text("type") !in setOf("function", "custom")) unsupported("Only named tool choices can be translated."); ToolChoice.Named(selected.text("name"), selected.optionalText("namespace"), if (selected.text("type") == "custom") ToolInputFormat.TEXT else ToolInputFormat.JSON) }
            else -> unsupported("Unsupported tool choice.")
        }
        if (choice is ToolChoice.Named && tools.none { it.name == choice.name && it.namespace == choice.namespace && it.inputFormat == choice.inputFormat }) {
            unsupported("Named tool choice does not match a declared tool.")
        }
        return Conversation(instructions, turns, tools, choice, body.bool("parallel_tool_calls"), reasoning?.optionalText("effort"), body.long("max_output_tokens"), body.double("temperature"), body.double("top_p"), emptyList(), mapOf(WireProtocol.RESPONSES to body.toString()), verbosity)
    }
    private fun responseParts(value: JsonElement?): List<Part> {
        if (value is JsonPrimitive && value.isString) return listOf(Part.Text(value.content))
        return (value as? JsonArray ?: unsupported("Expected text or a content array.")).map { element ->
            val part = element.obj()
            when (part.text("type")) {
                "input_text", "output_text" -> {
                    fields(part, setOf("type", "text", "annotations", "logprobs"))
                    if (part["annotations"]?.let { it != JsonNull && (it as? JsonArray)?.isEmpty() != true } == true) unsupported("Citations cannot be translated on this route.")
                    if (part["logprobs"]?.let { it != JsonNull && (it as? JsonArray)?.isEmpty() != true } == true) unsupported("Token probability metadata cannot be translated on this route.")
                    Part.Text(part.text("text"))
                }
                "input_image" -> { fields(part, setOf("type", "image_url", "detail")); Part.Image(part.text("image_url"), part.optionalText("detail")) }
                else -> unsupported("Unsupported Responses content block.")
            }
        }
    }
    override fun encode(c: Conversation, model: String): JsonObject {
        if (c.stops.isNotEmpty()) unsupported("The Codex subscription endpoint cannot preserve custom stop sequences.")
        // Claude Code sends the neutral API defaults explicitly. Codex subscription
        // models select those defaults without accepting sampling parameters on the wire.
        if (c.temperature !in setOf(null, 1.0) || c.topP !in setOf(null, 1.0)) unsupported("Non-default sampling overrides are not portable to Codex subscription models.")
        return buildJsonObject {
            put("model", model); put("stream", true); put("store", false)
            put("instructions", c.instructions.joinToString("\n\n"))
            putJsonArray("input") {
                c.turns.forEach { turn -> turn.content.forEach { part -> when (part) {
                    is Part.ToolCall -> add(buildJsonObject { put("type", if (part.inputFormat == ToolInputFormat.TEXT) "custom_tool_call" else "function_call"); put("call_id", part.id); put("name", part.name); part.namespace?.let { put("namespace", it) }; put(if (part.inputFormat == ToolInputFormat.TEXT) "input" else "arguments", part.arguments) })
                    is Part.ToolResult -> add(buildJsonObject {
                        put("type", if (part.inputFormat == ToolInputFormat.TEXT) "custom_tool_call_output" else "function_call_output"); put("call_id", part.id)
                        // Responses represents execution failures in tool output, not a
                        // separate is_error field. Keep every original content block and
                        // explicitly identify failure inside that same untrusted tool result.
                        val content = if (part.isError) listOf(Part.Text("[TeamCode tool result: is_error=true. Tool execution failed.]")) + part.content else part.content
                        put("output", JsonArray(content.map { responsePart(it, "user") }))
                    })
                    else -> add(buildJsonObject { put("type", "message"); put("role", turn.role); put("content", JsonArray(listOf(responsePart(part, turn.role)))) })
                } } }
            }
            if (c.tools.isNotEmpty()) put("tools", responseToolDefinitions(c.tools))
            put("tool_choice", when (val choice = c.toolChoice) { ToolChoice.Auto -> JsonPrimitive("auto"); ToolChoice.None -> JsonPrimitive("none"); ToolChoice.Required -> JsonPrimitive("required"); is ToolChoice.Named -> buildJsonObject { put("type", if (choice.inputFormat == ToolInputFormat.TEXT) "custom" else "function"); put("name", choice.name); choice.namespace?.let { put("namespace", it) } } })
            c.parallelTools?.let { put("parallel_tool_calls", it) }
            c.effort?.let { putJsonObject("reasoning") { put("effort", it) } }
            c.verbosity?.let { putJsonObject("text") { put("verbosity", it) } }
            c.maxOutputTokens?.let { put("max_output_tokens", it) }
        }
    }
    private fun responsePart(p: Part, role: String): JsonObject = when (p) {
        is Part.Text -> buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", p.text) }
        is Part.Image -> buildJsonObject { put("type", "input_image"); put("image_url", p.url); p.detail?.let { put("detail", it) } }
        else -> unsupported("Nested tool content is not portable.")
    }
}
