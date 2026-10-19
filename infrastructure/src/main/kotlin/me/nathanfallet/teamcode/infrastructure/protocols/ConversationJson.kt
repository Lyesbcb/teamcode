package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure


internal fun fields(value: JsonObject, allowed: Set<String>) {
    val unknown = value.keys - allowed
    if (unknown.isNotEmpty()) {
        // Reflect only bounded field identifiers to the requesting caller, never values.
        val names = unknown.take(8).map { if (Regex("[a-zA-Z_][a-zA-Z0-9_]{0,63}").matches(it)) it else "unrecognized_field" }.joinToString(", ")
        unsupported("This request contains fields that the cross-provider route cannot preserve: $names.")
    }
}
internal fun JsonElement?.obj() = this as? JsonObject ?: unsupported("Expected an object.")
internal fun JsonObject.text(key: String) = get(key)?.primitiveText() ?: unsupported("A required string is missing.")
internal fun JsonObject.optionalText(key: String) = get(key)?.takeIf { it != JsonNull }?.primitiveText()
internal fun JsonElement.primitiveText() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: unsupported("Expected a string.")
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: unsupported("Expected an array.")
internal fun JsonObject.bool(key: String) = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.booleanOrNull ?: unsupported("Expected a boolean.") }
internal fun JsonObject.long(key: String) = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.longOrNull ?: unsupported("Expected an integer.") }
internal fun JsonObject.double(key: String) = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { n -> n.isFinite() } ?: unsupported("Expected a finite number.") }
internal fun json(value: String) = try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) { unsupported("Invalid tool JSON.") }
internal fun unsupported(message: String): Nothing = throw TeamFailure("translation_unsupported", 422, message)
