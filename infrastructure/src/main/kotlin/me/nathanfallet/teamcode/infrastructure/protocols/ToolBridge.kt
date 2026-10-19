package me.nathanfallet.teamcode.infrastructure.protocols

import java.security.MessageDigest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

/** Stable wire names let stateless replicas translate complete histories identically. */
internal fun toolWireName(name: String, namespace: String?, format: ToolInputFormat): String {
    val valid = Regex("[a-zA-Z0-9_-]{1,64}")
    if (!valid.matches(name) || namespace?.let { !valid.matches(it) } == true) unsupported("Unsupported tool or namespace identifier.")
    if (namespace == null && format == ToolInputFormat.JSON) return name
    val identity = JsonArray(listOf(namespace?.let(::JsonPrimitive) ?: JsonNull, JsonPrimitive(name), JsonPrimitive(format.name)))
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    return "tc_" + listOfNotNull(namespace, name).joinToString("_").take(32) + "_" + digest
}
internal fun ToolDefinition.wireName() = toolWireName(name, namespace, inputFormat)

/** No registry or provider state survives a request. Original identities remain caller-owned. */
internal class ToolBridge(tools: List<ToolDefinition>) {
    val byWireName: Map<String, ToolDefinition>
    init {
        if (tools.size > 128) unsupported("Too many tools for a cross-provider route.")
        byWireName = tools.associateBy { it.wireName() }
        if (byWireName.size != tools.size) unsupported("Tool definitions have duplicate or conflicting identities.")
        tools.forEach { if (it.inputFormat == ToolInputFormat.TEXT) textMinimumLength(it.grammar) }
    }
    fun definitions(): JsonArray = JsonArray(byWireName.map { (wireName, tool) ->
        buildJsonObject {
            put("name", wireName)
            val description = buildList {
                if (wireName != tool.name) add("Original tool: ${listOfNotNull(tool.namespace, tool.name).joinToString(".")}.")
                tool.description?.let(::add)
                if (tool.inputFormat == ToolInputFormat.TEXT) add("Put the complete original tool input in the input string, without JSON encoding it a second time.")
            }.joinToString("\n\n")
            if (description.isNotEmpty()) put("description", description)
            put("input_schema", if (tool.inputFormat == ToolInputFormat.JSON) json(tool.schema) else textInputSchema(tool.grammar))
            (if (tool.inputFormat == ToolInputFormat.TEXT) true else tool.strict)?.let { put("strict", it) }
        }
    }).also { if (it.toString().toByteArray().size > 2 * 1024 * 1024) unsupported("Translated tools exceed the request limit.") }
}

internal fun textInputSchema(grammar: ToolGrammar?) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { putJsonObject("input") { put("type", "string"); put("minLength", textMinimumLength(grammar)) } }
    put("required", JsonArray(listOf(JsonPrimitive("input"))))
    put("additionalProperties", false)
}

/** The only grammar family currently equivalent to a JSON string is unrestricted text.
 * Other grammars need a real validator, never a description-only approximation. */
internal fun textMinimumLength(grammar: ToolGrammar?): Int {
    if (grammar == null) return 0
    val definition = grammar.definition.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString("\n")
    if (grammar.syntax == "regex") return when (definition) {
        "[\\s\\S]*" -> 0
        "[\\s\\S]+" -> 1
        else -> unsupported("This custom-tool grammar has no equivalent validated translation.")
    }
    if (grammar.syntax == "lark" && definition == CODE_MODE_TEXT_GRAMMAR) return 1
    unsupported("This custom-tool grammar has no equivalent validated translation.")
}

// The optional execution pragma does not narrow the language: plain_source accepts
// every nonempty string. Preserve the grammar's nonempty constraint in both directions.
internal val CODE_MODE_TEXT_GRAMMAR = """
start: pragma_source | plain_source
pragma_source: PRAGMA_LINE NEWLINE SOURCE
plain_source: SOURCE
PRAGMA_LINE: /[ \t]*\/\/ @exec:[^\r\n]*/
NEWLINE: /\r?\n/
SOURCE: /[\s\S]+/
""".trimIndent()
