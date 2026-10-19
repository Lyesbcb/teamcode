package me.nathanfallet.teamcode.domain.routing

/** Opaque hashes only: client session identifiers and conversation content are never persisted. */
data class RequestAffinity(val sessionKey: String, val conversationKey: String, val requestedModel: String, val routeKey: String) {
    init {
        require(listOf(sessionKey, conversationKey, routeKey).all { Regex("[a-f0-9]{64}").matches(it) })
        require(Regex("[a-zA-Z0-9_.-]{1,120}").matches(requestedModel))
    }
}
