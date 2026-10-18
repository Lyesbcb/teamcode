package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher

/** The row lock serializes refresh across processes and excludes concurrent import overwrites. */
class SqlCredentialSource(
    private val database: Database,
    private val cipher: CredentialCipher,
    private val renew: suspend (ProviderCredentials) -> ProviderCredentials,
) : CredentialSource {
    override suspend fun fresh(account: AccountRecord): ProviderCredentials {
        val result = database.suspendingTransaction { c ->
            val row = c.rows("SELECT * FROM accounts WHERE id = ? AND team_id = ? FOR UPDATE", account.id, account.identity.teamId) {
                Triple(accountRecord(it), it.getString("credential_envelope"), it.getLong("credential_version"))
            }.singleOrNull() ?: throw TeamFailure("account_unavailable", 503, "Account is no longer available.")
            if (row.first.disabled || !row.first.state.canServe) throw TeamFailure("account_unavailable", 503, "Account is disabled or awaiting reconnection/handover.")
            val context = "${account.identity.teamId}:${account.id}"
            val payload = Json.decodeFromString<CredentialPayload>(cipher.decrypt(row.second, context).decodeToString())
            val credentials = ProviderCredentials(payload.accessToken, payload.refreshToken, payload.expiresAtMillis)
            if (credentials.expiresAtMillis > System.currentTimeMillis() + 60_000) return@suspendingTransaction Result.success(credentials)
            if (row.first.state == AccountState.ACCESS_ONLY) return@suspendingTransaction Result.failure<ProviderCredentials>(
                TeamFailure("access_token_expired", 503, "Access-only credentials expired. Import current credentials again; TeamCode will not refresh this account."))
            try {
                val renewed = renew(credentials) // Adapter enforces a short timeout and never retries a rotating refresh.
                val envelope = cipher.encrypt(Json.encodeToString(CredentialPayload(renewed.accessToken, renewed.refreshToken, renewed.expiresAtMillis)).toByteArray(), context)
                c.execute("UPDATE accounts SET credential_envelope = ?, credential_version = credential_version + 1 WHERE id = ?", envelope, account.id)
                Result.success(renewed)
            } catch (error: Exception) {
                // An interrupted refresh may have rotated remotely. Do not reuse an uncertain refresh token.
                c.execute("UPDATE accounts SET state = 'RECONNECT_REQUIRED' WHERE id = ?", account.id)
                Result.failure(if (error is CancellationException) error else TeamFailure("provider_reconnect", 503, "Account refresh failed or was interrupted. Reconnect it from the CLI."))
            }
        }
        return result.getOrThrow()
    }
}
