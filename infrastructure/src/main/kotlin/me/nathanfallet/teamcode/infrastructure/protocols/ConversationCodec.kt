package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Native requests never pass through this codec. Unsupported cross-protocol semantics fail explicitly. */
class ConversationCodec {
    fun decode(body: JsonObject, protocol: WireProtocol): Conversation = when (protocol) {
        WireProtocol.MESSAGES -> messages(body)
        WireProtocol.RESPONSES -> responses(body)
    }
    fun encode(value: Conversation, protocol: WireProtocol, model: String): JsonObject = when (protocol) {
        WireProtocol.MESSAGES -> toMessages(value, model)
        WireProtocol.RESPONSES -> toResponses(value, model)
    }
    private fun messages(body: JsonObject): Conversation {
        fields(body, setOf("model", "stream", "max_tokens", "messages", "system", "tools", "tool_choice", "thinking", "output_config", "temperature", "top_p", "stop_sequences", "metadata", "cache_control"))
        val output = body["output_config"] as? JsonObject
        output?.let { fields(it, setOf("effort")) }
        val thinking = body["thinking"] as? JsonObject
        if (thinking != null && thinking.text("type") !in setOf("adaptive", "disabled")) unsupported("Explicit thinking budgets or signed reasoning cannot move between providers.")
        val instructions = textBlocks(body["system"])
        val turns = body.array("messages").map { item ->
            val m = item.obj(); fields(m, setOf("role", "content"))
            val role = m.text("role").also { if (it !in setOf("user", "assistant")) unsupported("Unsupported message role.") }
            Turn(role, anthropicParts(m["content"]))
        }
        val tools = (body["tools"] as? JsonArray).orEmpty().map { item ->
            val tool = item.obj(); fields(tool, setOf("name", "description", "input_schema", "strict", "cache_control"))
            ToolDefinition(tool.text("name"), tool.optionalText("description"), tool["input_schema"]?.toString() ?: "{}", tool.bool("strict"))
        }
        val choice = body["tool_choice"] as? JsonObject
        choice?.let { fields(it, setOf("type", "name", "disable_parallel_tool_use")) }
        val selected = when (choice?.optionalText("type")) { null, "auto" -> ToolChoice.Auto; "none" -> ToolChoice.None; "any" -> ToolChoice.Required; "tool" -> ToolChoice.Named(choice.text("name")); else -> unsupported("Unsupported tool choice.") }
        return Conversation(instructions, turns, tools, selected, choice?.bool("disable_parallel_tool_use")?.not(), (if (thinking?.text("type") == "disabled") "none" else output?.optionalText("effort")), body.long("max_tokens"), body.double("temperature"), body.double("top_p"), (body["stop_sequences"] as? JsonArray).orEmpty().map { it.primitiveText() }, mapOf(WireProtocol.MESSAGES to body.toString()))
    }
    private fun anthropicParts(content: JsonElement?): List<Part> {
        if (content is JsonPrimitive && content.isString) return listOf(Part.Text(content.content))
        return (content as? JsonArray ?: unsupported("Expected a text string or content array.")).map { item ->
            val block = item.obj()
            when (block.text("type")) {
                "text" -> { fields(block, setOf("type", "text", "cache_control")); Part.Text(block.text("text")) }
                "image" -> {
                    fields(block, setOf("type", "source", "cache_control"))
                    val source = block["source"].obj()
                    val url = when (source.text("type")) {
                        "base64" -> { fields(source, setOf("type", "media_type", "data")); "data:${source.text("media_type")};base64,${source.text("data")}" }
                        "url" -> { fields(source, setOf("type", "url")); source.text("url") }
                        else -> unsupported("Unsupported image source.")
                    }
                    Part.Image(url)
                }
                "tool_use" -> { fields(block, setOf("type", "id", "name", "input", "cache_control")); Part.ToolCall(block.text("id"), block.text("name"), block["input"]?.toString() ?: "{}") }
                "tool_result" -> {
                    fields(block, setOf("type", "tool_use_id", "content", "is_error", "cache_control"))
                    val parts = anthropicParts(block["content"] ?: JsonPrimitive(""))
                    if (parts.any { it !is Part.Text && it !is Part.Image }) unsupported("Nested tool results are not supported.")
                    Part.ToolResult(block.text("tool_use_id"), parts, block.bool("is_error") ?: false)
                }
                else -> unsupported("This content block cannot be translated without losing provider-specific semantics.")
            }
        }
    }
    private fun responses(body: JsonObject): Conversation {
        fields(body, setOf("model", "stream", "store", "instructions", "input", "tools", "tool_choice", "parallel_tool_calls", "reasoning", "max_output_tokens", "temperature", "top_p", "metadata", "include", "text", "prompt_cache_key", "prompt_cache_retention", "service_tier", "safety_identifier"))
        if (body["store"] == JsonPrimitive(true)) unsupported("Provider-side stored state cannot be translated.")
        val include = (body["include"] as? JsonArray).orEmpty()
        if (include.any { it.primitiveText() != "reasoning.encrypted_content" }) unsupported("This response include option is unsupported by the target protocol.")
        // An encrypted-content request is advisory for stateless reasoning replay. Opaque items in input still fail below.
        val reasoning = body["reasoning"] as? JsonObject
        reasoning?.let { fields(it, setOf("effort", "summary")) }
        if (reasoning?.optionalText("summary") !in setOf(null, "auto", "none")) unsupported("Explicit reasoning summary formats cannot be translated.")
        val text = body["text"] as? JsonObject
        text?.let {
            fields(it, setOf("format"))
            val format = it["format"] as? JsonObject
            if (format != null && (format.size != 1 || format.text("type") != "text")) unsupported("Structured output translation is not enabled.")
        }
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
                "function_call" -> {
                    fields(value, setOf("type", "id", "call_id", "name", "arguments", "status"))
                    turns += Turn("assistant", listOf(Part.ToolCall(value.text("call_id"), value.text("name"), value.text("arguments"))))
                }
                "function_call_output" -> {
                    fields(value, setOf("type", "id", "call_id", "output", "status"))
                    turns += Turn("user", listOf(Part.ToolResult(value.text("call_id"), responseParts(value["output"]))))
                }
                else -> unsupported("Opaque reasoning, built-in tools and provider state cannot be translated safely.")
            }
        }
        val tools = (body["tools"] as? JsonArray).orEmpty().map { item ->
            val tool = item.obj()
            if (tool.text("type") != "function") unsupported("Only function tools can cross this route.")
            fields(tool, setOf("type", "name", "description", "parameters", "strict"))
            ToolDefinition(tool.text("name"), tool.optionalText("description"), tool["parameters"]?.toString() ?: "{}", tool.bool("strict"))
        }
        val choice = when (val selected = body["tool_choice"]) {
            null -> ToolChoice.Auto
            is JsonPrimitive -> when (selected.content) { "auto" -> ToolChoice.Auto; "none" -> ToolChoice.None; "required" -> ToolChoice.Required; else -> unsupported("Unsupported tool choice.") }
            is JsonObject -> { fields(selected, setOf("type", "name")); if (selected.text("type") != "function") unsupported("Only named function choices can be translated."); ToolChoice.Named(selected.text("name")) }
            else -> unsupported("Unsupported tool choice.")
        }
        return Conversation(instructions, turns, tools, choice, body.bool("parallel_tool_calls"), reasoning?.optionalText("effort"), body.long("max_output_tokens"), body.double("temperature"), body.double("top_p"), emptyList(), mapOf(WireProtocol.RESPONSES to body.toString()))
    }
    private fun responseParts(value: JsonElement?): List<Part> {
        if (value is JsonPrimitive && value.isString) return listOf(Part.Text(value.content))
        return (value as? JsonArray ?: unsupported("Expected text or a content array.")).map { element ->
            val part = element.obj()
            when (part.text("type")) {
                "input_text", "output_text" -> { fields(part, setOf("type", "text", "annotations")); if ((part["annotations"] as? JsonArray)?.isNotEmpty() == true) unsupported("Citations cannot be translated on this route."); Part.Text(part.text("text")) }
                "input_image" -> { fields(part, setOf("type", "image_url", "detail")); Part.Image(part.text("image_url"), part.optionalText("detail")) }
                else -> unsupported("Unsupported Responses content block.")
            }
        }
    }
    private fun toResponses(c: Conversation, model: String): JsonObject {
        if (c.stops.isNotEmpty()) unsupported("The Codex subscription endpoint cannot preserve custom stop sequences.")
        if (c.temperature != null || c.topP != null) unsupported("Sampling overrides are not portable to Codex subscription models.")
        if (c.turns.flatMap { it.content }.filterIsInstance<Part.ToolResult>().any { it.isError }) unsupported("This tool error marker has no equivalent in Responses.")
        return buildJsonObject {
            put("model", model); put("stream", true); put("store", false)
            put("instructions", c.instructions.joinToString("\n\n"))
            putJsonArray("input") {
                c.turns.forEach { turn -> turn.content.forEach { part -> when (part) {
                    is Part.ToolCall -> add(buildJsonObject { put("type", "function_call"); put("call_id", part.id); put("name", part.name); put("arguments", part.arguments) })
                    is Part.ToolResult -> add(buildJsonObject { put("type", "function_call_output"); put("call_id", part.id); put("output", JsonArray(part.content.map { responsePart(it, "user") })) })
                    else -> add(buildJsonObject { put("type", "message"); put("role", turn.role); put("content", JsonArray(listOf(responsePart(part, turn.role)))) })
                } } }
            }
            if (c.tools.isNotEmpty()) putJsonArray("tools") { c.tools.forEach { tool -> add(buildJsonObject {
                put("type", "function"); put("name", tool.name); tool.description?.let { put("description", it) }; put("parameters", json(tool.schema)); tool.strict?.let { put("strict", it) }
            }) } }
            put("tool_choice", when (val choice = c.toolChoice) { ToolChoice.Auto -> JsonPrimitive("auto"); ToolChoice.None -> JsonPrimitive("none"); ToolChoice.Required -> JsonPrimitive("required"); is ToolChoice.Named -> buildJsonObject { put("type", "function"); put("name", choice.name) } })
            c.parallelTools?.let { put("parallel_tool_calls", it) }
            c.effort?.let { putJsonObject("reasoning") { put("effort", it) } }
            c.maxOutputTokens?.let { put("max_output_tokens", it) }
        }
    }
    private fun toMessages(c: Conversation, model: String): JsonObject = buildJsonObject {
        put("model", model); put("stream", true); put("max_tokens", c.maxOutputTokens ?: 16_384)
        if (c.instructions.isNotEmpty()) put("system", c.instructions.joinToString("\n\n"))
        val merged = mutableListOf<Turn>()
        c.turns.forEach { turn ->
            if (merged.lastOrNull()?.role == turn.role) merged[merged.lastIndex] = merged.last().copy(content = merged.last().content + turn.content)
            else merged += turn
        }
        putJsonArray("messages") { merged.forEach { turn -> add(buildJsonObject { put("role", turn.role); put("content", JsonArray(turn.content.map(::anthropicPart))) }) } }
        if (c.tools.isNotEmpty()) putJsonArray("tools") { c.tools.forEach { tool -> add(buildJsonObject { put("name", tool.name); tool.description?.let { put("description", it) }; put("input_schema", json(tool.schema)); tool.strict?.let { put("strict", it) } }) } }
        putJsonObject("tool_choice") {
            when (val choice = c.toolChoice) { ToolChoice.Auto -> put("type", "auto"); ToolChoice.None -> put("type", "none"); ToolChoice.Required -> put("type", "any"); is ToolChoice.Named -> { put("type", "tool"); put("name", choice.name) } }
            c.parallelTools?.let { put("disable_parallel_tool_use", !it) }
        }
        if (c.effort == "none") putJsonObject("thinking") { put("type", "disabled") }
        else c.effort?.let { putJsonObject("output_config") { put("effort", it) } }
        c.temperature?.let { put("temperature", it) }; c.topP?.let { put("top_p", it) }
    }
    private fun responsePart(p: Part, role: String): JsonObject = when (p) {
        is Part.Text -> buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", p.text) }
        is Part.Image -> buildJsonObject { put("type", "input_image"); put("image_url", p.url); p.detail?.let { put("detail", it) } }
        else -> unsupported("Nested tool content is not portable.")
    }
    private fun anthropicPart(p: Part): JsonObject = when (p) {
        is Part.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
        is Part.Image -> {
            if (p.detail !in setOf(null, "auto")) unsupported("Explicit image detail cannot be preserved in Messages.")
            buildJsonObject { put("type", "image"); putJsonObject("source") {
                if (p.url.startsWith("data:")) {
                    val match = Regex("data:(image/[a-zA-Z0-9.+-]+);base64,(.*)", RegexOption.DOT_MATCHES_ALL).matchEntire(p.url) ?: unsupported("Unsupported image data URL.")
                    put("type", "base64"); put("media_type", match.groupValues[1]); put("data", match.groupValues[2])
                } else { put("type", "url"); put("url", p.url) }
            } }
        }
        is Part.ToolCall -> buildJsonObject { put("type", "tool_use"); put("id", p.id); put("name", p.name); put("input", json(p.arguments)) }
        is Part.ToolResult -> buildJsonObject { put("type", "tool_result"); put("tool_use_id", p.id); put("content", JsonArray(p.content.map(::anthropicPart))); if (p.isError) put("is_error", true) }
    }
    private fun textBlocks(value: JsonElement?): List<String> = when (value) {
        null -> emptyList()
        is JsonPrimitive -> listOf(value.primitiveText())
        is JsonArray -> value.map { it.obj().also { b -> fields(b, setOf("type", "text", "cache_control")); if (b.text("type") != "text") unsupported("Instructions must be text.") }.text("text") }
        else -> unsupported("Unsupported instructions.")
    }
    private fun fields(value: JsonObject, allowed: Set<String>) { if (value.keys.any { it !in allowed }) unsupported("This request contains fields that the cross-provider route cannot preserve.") }
    private fun JsonElement?.obj() = this as? JsonObject ?: unsupported("Expected an object.")
    private fun JsonObject.text(key: String) = get(key)?.primitiveText() ?: unsupported("A required string is missing.")
    private fun JsonObject.optionalText(key: String) = get(key)?.takeIf { it != JsonNull }?.primitiveText()
    private fun JsonElement.primitiveText() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: unsupported("Expected a string.")
    private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: unsupported("Expected an array.")
    private fun JsonObject.bool(key: String) = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.booleanOrNull ?: unsupported("Expected a boolean.") }
    private fun JsonObject.long(key: String) = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.longOrNull ?: unsupported("Expected an integer.") }
    private fun JsonObject.double(key: String) = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { n -> n.isFinite() } ?: unsupported("Expected a finite number.") }
    private fun json(value: String) = try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) { unsupported("Invalid tool JSON.") }
    private fun unsupported(message: String): Nothing = throw TeamFailure("translation_unsupported", 422, message)
}
