package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.infrastructure.protocols.stream.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** A protocol adapter describes request and stream formats, independently of the provider hosting it. */
interface ProtocolAdapter {
    val protocol: WireProtocol
    fun decode(body: JsonObject): Conversation
    fun encode(c: Conversation, model: String): JsonObject
    fun decoder(): StreamDecoder
    fun encoder(model: String, tools: List<ToolDefinition>? = null): StreamEncoder
}

/** Native requests never pass through this registry. Conversions fail rather than lose semantics. */
class ConversationCodec(codecs: List<ProtocolAdapter> = listOf(MessagesProtocolAdapter(), ResponsesProtocolAdapter())) {
    private val protocols = codecs.associateBy { it.protocol }.also {
        require(it.size == codecs.size) { "Each wire protocol must have exactly one codec." }
    }
    private fun codec(protocol: WireProtocol) = protocols[protocol]
        ?: throw TeamFailure("translation_unsupported", 422, "This wire protocol has no registered conversation codec.")
    fun decoder(protocol: WireProtocol) = codec(protocol).decoder()
    fun encoder(protocol: WireProtocol, model: String, tools: List<ToolDefinition>? = null) = codec(protocol).encoder(model, tools)
    fun decode(body: JsonObject, protocol: WireProtocol): Conversation = codec(protocol).decode(body)
    fun encode(value: Conversation, protocol: WireProtocol, model: String): JsonObject = codec(protocol).encode(value, model)
}
