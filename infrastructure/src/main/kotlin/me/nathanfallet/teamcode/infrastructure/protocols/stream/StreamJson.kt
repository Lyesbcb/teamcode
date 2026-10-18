package me.nathanfallet.teamcode.infrastructure.protocols.stream

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

internal fun JsonElement?.obj() = this as? JsonObject ?: unsupportedStream()
internal fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.required(key: String) = str(key) ?: unsupportedStream()
internal fun JsonObject.index(key: String) = (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..255 } ?: unsupportedStream()
internal fun unsupportedStream(category: String = "invalid"): Nothing = throw TeamFailure("translation_stream_$category", 502, "The provider stream contains unsupported or invalid translation semantics.")
