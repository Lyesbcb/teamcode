package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher

/** Commit refresh intent before contacting the provider: even a killed pod cannot replay a rotating token. */
class SqlCredentialSource(
    private val database: Database,
    private val cipher: CredentialCipher,
    private val renew: suspend (AccountRecord, ProviderCredentials) -> ProviderCredentials,
) : CredentialSource {
    override suspend fun fresh(account: AccountRecord): ProviderCredentials {
        // Other replicas can briefly wait for the refresh owner. They never take over an uncertain refresh.
        repeat(400) {
            val attempt = database.transaction { c ->
                val row = c.rows("SELECT * FROM accounts WHERE id = ? AND team_id = ? FOR UPDATE", account.id, account.identity.teamId) {
                    RefreshRow(accountRecord(it), it.getString("credential_envelope"), it.getBoolean("refresh_pending"), it.getLong("refresh_started_at"))
                }.singleOrNull() ?: throw unavailable()
                if (row.account.disabled || !row.account.state.canServe) throw unavailable()
                val now = c.nowMillis()
                if (row.pending) {
                    if (row.startedAt + 60_000 <= now) {
                        c.execute("UPDATE accounts SET state = 'RECONNECT_REQUIRED' WHERE id = ?", account.id)
                        return@transaction Attempt.Failed
                    }
                    return@transaction Attempt.Wait
                }
                val payload = Json.decodeFromString<CredentialPayload>(cipher.decrypt(row.envelope, "${account.identity.teamId}:${account.id}").decodeToString())
                val credentials = ProviderCredentials(payload.accessToken, payload.refreshToken, payload.expiresAtMillis)
                if (credentials.expiresAtMillis > now + 60_000) return@transaction Attempt.Ready(credentials)
                if (row.account.state == AccountState.ACCESS_ONLY) throw TeamFailure("access_token_expired", 503, "Access-only credentials expired. Import current credentials again; TeamCode will not refresh this account.")
                c.execute("UPDATE accounts SET refresh_pending = TRUE, refresh_started_at = ? WHERE id = ?", now, account.id)
                Attempt.Owner(row.account, credentials)
            }
            when (attempt) {
                is Attempt.Ready -> return attempt.credentials
                is Attempt.Owner -> return refresh(attempt)
                Attempt.Failed -> throw reconnect()
                Attempt.Wait -> delay(100)
            }
        }
        throw TeamFailure("refresh_in_progress", 503, "Account refresh is still in progress. Retry shortly.")
    }

    private suspend fun refresh(attempt: Attempt.Owner): ProviderCredentials {
        val account = attempt.account
        try {
            val renewed = withTimeout(45_000) { renew(account, attempt.credentials) }
            val envelope = cipher.encrypt(Json.encodeToString(CredentialPayload(renewed.accessToken, renewed.refreshToken, renewed.expiresAtMillis)).toByteArray(), "${account.identity.teamId}:${account.id}")
            val saved = database.transaction { c ->
                c.execute("UPDATE accounts SET credential_envelope = ?, credential_version = credential_version + 1, refresh_pending = FALSE WHERE id = ? AND credential_version = ? AND refresh_pending = TRUE AND state = 'READY'", envelope, account.id, account.credentialVersion) == 1
            }
            if (!saved) throw reconnect()
            return renewed
        } catch (error: Exception) {
            // Keep the pending marker until a verified import supplies new credentials. Handover cannot clear it.
            withContext(NonCancellable) { database.transaction { c ->
                c.execute("UPDATE accounts SET state = 'RECONNECT_REQUIRED' WHERE id = ? AND credential_version = ? AND refresh_pending = TRUE", account.id, account.credentialVersion)
            } }
            if (error is CancellationException) throw error
            throw reconnect()
        }
    }
    private fun unavailable() = TeamFailure("account_unavailable", 503, "Account is disabled or awaiting reconnection/handover.")
    private fun reconnect() = TeamFailure("provider_reconnect", 503, "Account refresh failed or was interrupted. Reconnect it from the CLI.")
    private data class RefreshRow(val account: AccountRecord, val envelope: String, val pending: Boolean, val startedAt: Long)
    private sealed interface Attempt {
        class Ready(val credentials: ProviderCredentials) : Attempt
        class Owner(val account: AccountRecord, val credentials: ProviderCredentials) : Attempt
        data object Wait : Attempt
        data object Failed : Attempt
    }
}
