package me.nathanfallet.teamcode.infrastructure.gateway

import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*

/** Cache affinity is a hint, never authorization. Fresh subagent openings get independent keys. */
@OptIn(ExperimentalSerializationApi::class)
internal object SessionAffinityKey {
    fun from(headers: Map<String, String>, body: JsonObject, protocol: WireProtocol, model: String, targets: List<RouteTarget>): RequestAffinity? {
        // Match TeamClaude precedence: an invalid specific header cannot fall back to a generic one.
        val id = headers["x-claude-code-session-id"] ?: headers["session-id"] ?: return null
        if (!Regex("[A-Za-z0-9._-]{1,128}").matches(id)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(id.toByteArray()); digest.update(0.toByte()); digest.update(protocol.name.toByteArray()); digest.update(0.toByte())
        val opening = when (protocol) {
            WireProtocol.MESSAGES -> (body["messages"] as? JsonArray)?.firstOrNull()
            WireProtocol.RESPONSES -> when (val input = body["input"]) {
                is JsonArray -> input.firstOrNull { (it as? JsonObject)?.get("role") == JsonPrimitive("user") } ?: input.firstOrNull()
                is JsonPrimitive -> input
                else -> null
            }
        }
        if (opening != null) {
            // The request is already parsed for policy inspection. Stream just the bounded opening
            // into the digest, without allocating another complete message or serializing later turns.
            val sink = object : OutputStream() {
                var remaining = 65_536
                override fun write(value: Int) {
                    if (remaining == 0) throw PrefixComplete()
                    digest.update(value.toByte()); remaining--
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    val take = minOf(remaining, length)
                    digest.update(bytes, offset, take); remaining -= take
                    if (take < length) throw PrefixComplete()
                }
            }
            try { Json.encodeToStream(JsonElement.serializer(), opening, sink) } catch (_: PrefixComplete) { /* A shared prefix is a harmless affinity collision. */ }
        }
        val route = targets.joinToString("\n") { "${it.provider}:${it.model ?: model}" }
        return RequestAffinity(hash(id), digest.digest().hex(), model, hash("${protocol.name}\n$route"))
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private class PrefixComplete : RuntimeException(null, null, false, false)
}
