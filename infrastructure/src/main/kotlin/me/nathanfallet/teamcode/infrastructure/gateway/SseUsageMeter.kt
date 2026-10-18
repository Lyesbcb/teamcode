package me.nathanfallet.teamcode.infrastructure.gateway

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** Reads usage metadata only; no prompt or generated content is retained after a bounded event line. */
class SseUsageMeter {
    private val line = ByteArrayOutputStream()
    private var invalid = false
    private var dropping = false
    private var input: Long? = null
    private var cacheRead = 0L
    private var cacheCreate = 0L
    private var output: Long? = null
    private var complete = false
    fun accept(bytes: ByteArray) {
        for (byte in bytes) {
            if (byte == 10.toByte()) {
                if (!dropping) consume(line.toString(Charsets.UTF_8))
                line.reset(); dropping = false
            } else if (!dropping) {
                if (line.size() >= 262_144) { invalid = true; dropping = true; line.reset() }
                else line.write(byte.toInt())
            }
        }
    }
    val settledUnits: Long? get() {
        if (!complete || invalid || input == null || output == null) return null
        return input!! + cacheRead + cacheCreate + output!! * 5
    }
    private fun consume(text: String) {
        if (!text.startsWith("data:")) return
        val data = text.removePrefix("data:").trim()
        val json = try { Json.parseToJsonElement(data) as? JsonObject } catch (_: IllegalArgumentException) { null }
        if (json == null) { invalid = true; return }
        when ((json["type"] as? JsonPrimitive)?.contentOrNull) {
            "error" -> invalid = true
            "message_stop" -> complete = true
            "message_start" -> record((json["message"] as? JsonObject)?.get("usage") as? JsonObject)
            "message_delta" -> record(json["usage"] as? JsonObject)
        }
    }
    private fun record(usage: JsonObject?) {
        if (usage == null) return
        fun field(name: String): Long? {
            val value = usage[name] ?: return null
            val number = (value as? JsonPrimitive)?.longOrNull
            if (number == null || number !in 0..10_000_000) { invalid = true; return null }
            return number
        }
        field("input_tokens")?.let { input = maxOf(input ?: 0, it) }
        field("output_tokens")?.let { output = maxOf(output ?: 0, it) }
        field("cache_read_input_tokens")?.let { cacheRead = maxOf(cacheRead, it) }
        field("cache_creation_input_tokens")?.let { cacheCreate = maxOf(cacheCreate, it) }
    }
}
