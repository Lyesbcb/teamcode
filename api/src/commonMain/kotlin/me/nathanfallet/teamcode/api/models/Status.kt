package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class Capabilities(
    val apiVersion: Int = 1,
    val serverVersion: String,
    val mode: String,
    val features: List<String>,
)

@Serializable
data class GatewayStatus(val mode: String, val activeRequests: Int, val completedRequests: Long, val queuedRequests: Int = 0)

@Serializable
data class ApiError(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val type: String, val message: String)
