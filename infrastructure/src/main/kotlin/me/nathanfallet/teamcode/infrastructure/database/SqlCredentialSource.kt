package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.infrastructure.database.tables.Accounts
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
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
    override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?): ProviderCredentials {
        // Other replicas can briefly wait for the refresh owner. They never take over an uncertain refresh.
        repeat(400) {
            val attempt = database.query {
                val row = Accounts.selectAll().where { (Accounts.id eq account.id) and (Accounts.teamId eq account.identity.teamId) }.forUpdate()
                    .singleOrNull()?.let { RefreshRow(accountRecord(it), it[Accounts.credentialEnvelope], it[Accounts.refreshPending], it[Accounts.refreshStartedAt]) } ?: throw unavailable()
                if (row.account.disabled || !row.account.state.canServe) throw unavailable()
                val now = nowMillis()
                if (row.pending) {
                    if (row.startedAt + 60_000 <= now) {
                        Accounts.update({ Accounts.id eq account.id }) { it[state] = "RECONNECT_REQUIRED" }
                        return@query Attempt.Failed
                    }
                    return@query Attempt.Wait
                }
                val payload = Json.decodeFromString<CredentialPayload>(cipher.decrypt(row.envelope, "${account.identity.teamId}:${account.id}").decodeToString())
                val credentials = ProviderCredentials(payload.accessToken, payload.refreshToken, payload.expiresAtMillis)
                if (credentials.expiresAtMillis > now + 60_000 && credentials.accessToken != rejectedAccessToken) return@query Attempt.Ready(credentials)
                if (row.account.state == AccountState.ACCESS_ONLY) throw TeamFailure("access_token_expired", 503, "Access-only credentials expired. Import current credentials again; TeamCode will not refresh this account.")
                Accounts.update({ Accounts.id eq account.id }) { it[refreshPending] = true; it[refreshStartedAt] = now }
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
            val saved = database.query {
                Accounts.update({ (Accounts.id eq account.id) and (Accounts.credentialVersion eq account.credentialVersion) and (Accounts.refreshPending eq true) and (Accounts.state eq "READY") }) {
                    it[credentialEnvelope] = envelope; it[credentialVersion] = Accounts.credentialVersion + 1; it[refreshPending] = false
                } == 1
            }
            if (!saved) throw reconnect()
            return renewed
        } catch (error: Exception) {
            // Keep the pending marker until a verified import supplies new credentials. Handover cannot clear it.
            withContext(NonCancellable) { database.query {
                Accounts.update({ (Accounts.id eq account.id) and (Accounts.credentialVersion eq account.credentialVersion) and (Accounts.refreshPending eq true) }) { it[state] = "RECONNECT_REQUIRED" }
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
