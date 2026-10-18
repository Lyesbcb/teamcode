package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class DirectiveView(val id: String, val pace: String, val maxConcurrent: Int, val minIntervalMillis: Long, val message: String, val createdAtMillis: Long, val expiresAtMillis: Long)
@Serializable
data class DirectiveResponse(val directive: DirectiveView?)
@Serializable
data class DirectiveReceipt(val id: String, val stage: String)
