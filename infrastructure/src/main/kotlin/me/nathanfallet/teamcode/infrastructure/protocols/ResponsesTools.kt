package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

/** Merge explicit Responses tool declarations, including developer additional_tools items. */
internal class ResponsesTools {
    private val definitions = linkedMapOf<Pair<String?, String>, ToolDefinition>()
    private var descriptionBytes = 0
    fun add(values: JsonArray, namespace: String? = null, namespaceDescription: String? = null) {
        if (values.size > 128) unsupported("Too many tools for a cross-provider route.")
        values.forEach { element ->
            val tool = element.obj()
            val type = tool.text("type")
            if (type == "namespace") {
                fields(tool, setOf("type", "name", "description", "tools"))
                if (namespace != null) unsupported("Nested tool namespaces are not supported.")
                val name = tool.text("name")
                toolWireName("validate", name, ToolInputFormat.JSON)
                add(tool.array("tools"), name, tool.optionalText("description"))
            } else {
                if (type !in setOf("function", "custom")) unsupported("Only function and supported text tools can cross this route.")
                fields(tool, setOf("type", "name", "description", "parameters", "strict", "format"))
                val kind = if (type == "custom") ToolInputFormat.TEXT else ToolInputFormat.JSON
                if ((kind == ToolInputFormat.JSON && tool["format"] != null) ||
                    (kind == ToolInputFormat.TEXT && (tool["parameters"] != null || tool["strict"] != null))) unsupported("Tool definition mixes incompatible input formats.")
                val grammar = if (kind == ToolInputFormat.TEXT) format(tool["format"]) else null
                val description = listOfNotNull(namespaceDescription?.let { "Namespace $namespace: $it" }, tool.optionalText("description")).joinToString("\n\n").ifEmpty { null }
                descriptionBytes += description?.toByteArray()?.size ?: 0
                if (descriptionBytes > 2 * 1024 * 1024) unsupported("Translated tools exceed the request limit.")
                val definition = ToolDefinition(tool.text("name"), description, tool["parameters"]?.toString() ?: "{}", tool.bool("strict"), namespace, kind, grammar)
                definition.wireName() // Validate identifiers before constructing a provider request.
                val key = namespace to definition.name
                val previous = definitions.putIfAbsent(key, definition)
                if (previous != null && previous != definition) unsupported("Tool definitions have conflicting identities.")
                if (definitions.size > 128) unsupported("Too many tools for a cross-provider route.")
            }
        }
    }
    fun values(): List<ToolDefinition> = definitions.values.toList().also { ToolBridge(it) }
    private fun format(value: JsonElement?): ToolGrammar? {
        if (value == null || value == JsonNull) return null
        val format = value.obj()
        return when (format.text("type")) {
            "text" -> { fields(format, setOf("type")); null }
            "grammar" -> {
                fields(format, setOf("type", "syntax", "definition"))
                ToolGrammar(format.text("syntax"), format.text("definition")).also { textMinimumLength(it) }
            }
            else -> unsupported("Unsupported custom-tool input format.")
        }
    }
}

internal fun responseToolDefinitions(tools: List<ToolDefinition>): JsonArray {
    fun definition(tool: ToolDefinition) = buildJsonObject {
        put("type", if (tool.inputFormat == ToolInputFormat.TEXT) "custom" else "function")
        put("name", tool.name); tool.description?.let { put("description", it) }
        if (tool.inputFormat == ToolInputFormat.TEXT) putJsonObject("format") {
            val grammar = tool.grammar
            if (grammar == null) put("type", "text")
            else { put("type", "grammar"); put("syntax", grammar.syntax); put("definition", grammar.definition) }
        } else {
            put("parameters", json(tool.schema)); tool.strict?.let { put("strict", it) }
        }
    }
    return JsonArray(tools.groupBy { it.namespace }.flatMap { (namespace, entries) ->
        if (namespace == null) entries.map(::definition) else listOf(buildJsonObject {
            put("type", "namespace"); put("name", namespace); put("tools", JsonArray(entries.map(::definition)))
        })
    })
}
