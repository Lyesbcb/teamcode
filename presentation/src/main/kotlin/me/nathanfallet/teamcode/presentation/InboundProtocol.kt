package me.nathanfallet.teamcode.presentation

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** HTTP ingress contract. Provider selection happens later and is independent of this protocol. */
interface InboundProtocol {
    val path: String
    val protocol: WireProtocol
    fun validateConversation(body: JsonObject)
}

class MessagesInboundProtocol : InboundProtocol {
    override val path = "/v1/messages"
    override val protocol = WireProtocol.MESSAGES
    override fun validateConversation(body: JsonObject) {
        if (body["messages"] !is JsonArray) throw TeamFailure("invalid_request", 400, "A messages array is required.")
    }
}

class ResponsesInboundProtocol : InboundProtocol {
    override val path = "/v1/responses"
    override val protocol = WireProtocol.RESPONSES
    override fun validateConversation(body: JsonObject) {
        if (body["input"] !is JsonArray && (body["input"] as? JsonPrimitive)?.isString != true)
            throw TeamFailure("invalid_request", 400, "An input string or array is required.")
    }
}
