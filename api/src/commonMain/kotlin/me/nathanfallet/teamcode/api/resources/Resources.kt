package me.nathanfallet.teamcode.api.resources

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/capabilities")
class CapabilitiesResource

@Serializable
@Resource("/api/v1/status")
class StatusResource
