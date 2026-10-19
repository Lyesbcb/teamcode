package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class DirectiveView(val id: String, val pace: String, val maxConcurrent: Int, val minIntervalMillis: Long, val message: String, val createdAtMillis: Long, val expiresAtMillis: Long)
@Serializable
data class DirectiveResponse(val directive: DirectiveView?)
@Serializable
data class DirectiveReceipt(val id: String, val stage: String, val sessionId: String? = null, val claimId: String? = null)
@Serializable
data class DirectiveScope(val sessionId: String)
@Serializable
data class DirectiveClaimRequest(val sessionId: String, val stage: String)
@Serializable
data class DirectiveClaimResponse(val directive: DirectiveView?, val claimId: String? = null)
