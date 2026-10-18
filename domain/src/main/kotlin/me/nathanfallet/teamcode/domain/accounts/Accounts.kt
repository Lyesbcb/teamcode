package me.nathanfallet.teamcode.domain.accounts

/** Provider identity is populated by a server-side verifier, never by display-name matching. */
data class AccountIdentity(val teamId: String, val provider: String, val accountId: String, val organizationId: String) {
    init { require(listOf(teamId, provider, accountId, organizationId).all { it.isNotBlank() }) }
}

class ProviderCredentials(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long) {
    init { require(accessToken.isNotBlank() && refreshToken.isNotBlank() && expiresAtMillis > 0) }
    override fun toString(): String = "ProviderCredentials([REDACTED])"
}

data class VerifiedProviderAccount(val identity: AccountIdentity, val displayName: String)
data class AccountRecord(
    val id: String,
    val identity: AccountIdentity,
    val displayName: String,
    val credentialVersion: Long,
    val disabled: Boolean,
    val poolId: String?,
    val priority: Int,
    val state: AccountState,
)
enum class AccountState { PENDING_HANDOVER, READY, ACCESS_ONLY, RECONNECT_REQUIRED;
    val canServe: Boolean get() = this == READY || this == ACCESS_ONLY
}

interface AccountVerifier {
    suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount
}

interface AccountsRepository {
    suspend fun find(identity: AccountIdentity): AccountRecord?
    /** Must atomically enforce identity uniqueness, version and metadata-preserving updates. */
    suspend fun upsert(verified: VerifiedProviderAccount, credentials: ProviderCredentials, expectedVersion: Long?, operationId: String): EnrollmentResult
}

sealed interface EnrollmentResult {
    data class Created(val account: AccountRecord) : EnrollmentResult
    data class Updated(val account: AccountRecord) : EnrollmentResult
    data object Conflict : EnrollmentResult
}

class CredentialVersionConflict : Exception("Account credentials changed during enrollment. Retry using current credentials.")

interface CredentialSource {
    /** Rejected access tokens force renewal only if they are still current in shared storage. */
    suspend fun fresh(account: AccountRecord, rejectedAccessToken: String? = null): ProviderCredentials
}
