package me.nathanfallet.teamcode.api.resources

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/capabilities")
class CapabilitiesResource

@Serializable
@Resource("/api/v1/status")
class StatusResource

/** Administrator-only observed history and forecasts for a selected subscription account. */
@Serializable
@Resource("/api/v1/forecasts")
class ForecastsResource(val account: String? = null)
