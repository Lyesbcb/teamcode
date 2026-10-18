package me.nathanfallet.teamcode.api

import kotlinx.serialization.json.Json

object Serialization {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
