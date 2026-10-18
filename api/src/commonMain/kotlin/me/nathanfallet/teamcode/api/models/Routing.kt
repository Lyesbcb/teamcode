package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class RouteTargetView(val provider: String, val model: String? = null)
@Serializable
data class RouteRuleView(val id: String, val pattern: String, val targets: List<RouteTargetView>, val priority: Int = 0, val enabled: Boolean = true, val version: Long = 0)
@Serializable
data class RouteDeleteRequest(val version: Long)
