package me.nathanfallet.teamcode.infrastructure.providers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Providers use aliases during schema transitions; explicit JSON null does not hide a later alias. */
internal fun JsonObject.quotaField(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { get(it)?.takeUnless { value -> value == JsonNull } }
