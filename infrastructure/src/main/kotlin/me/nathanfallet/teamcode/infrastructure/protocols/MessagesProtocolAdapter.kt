package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.infrastructure.protocols.stream.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*


class MessagesProtocolAdapter : ProtocolAdapter {
    override fun decoder(): StreamDecoder = MessagesStreamDecoder()
    override fun encoder(model: String): StreamEncoder = MessagesStreamEncoder(model)
    override val protocol = WireProtocol.MESSAGES
    override fun decode(body: JsonObject): Conversation {
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
            // Responses attempts strict-schema normalization when omitted; Claude
            // tools are best-effort by default. Keep optional arguments optional.
            ToolDefinition(tool.text("name"), tool.optionalText("description"), tool["input_schema"]?.toString() ?: "{}", tool.bool("strict") ?: false)
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
    override fun encode(c: Conversation, model: String): JsonObject = buildJsonObject {
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
}
