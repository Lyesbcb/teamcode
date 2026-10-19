package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class ServiceKeyView(val id: String, val memberId: String, val name: String, val scopes: List<String>, val createdAt: Long, val expiresAt: Long)
@Serializable
data class CreateServiceKeyRequest(val name: String, val scopes: List<String>, val validDays: Int = 30)
@Serializable
class CreatedServiceKeyResponse(val metadata: ServiceKeyView, val accessToken: String) {
    override fun toString() = "CreatedServiceKeyResponse(metadata=$metadata, accessToken=[REDACTED])"
}
