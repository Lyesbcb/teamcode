package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
class EnrollAccountRequest(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long, val operationId: String, val provider: String = "anthropic") {
    override fun toString() = "EnrollAccountRequest([REDACTED])"
}
@Serializable
data class AccountView(val id: String, val provider: String, val displayName: String, val credentialVersion: Long, val disabled: Boolean, val priority: Int, val state: String, val poolId: String? = null)
@Serializable
data class EnrollAccountResponse(val created: Boolean, val account: AccountView)
@Serializable
data class UpdateAccountRequest(val displayName: String, val disabled: Boolean, val priority: Int, val poolId: String? = null)
