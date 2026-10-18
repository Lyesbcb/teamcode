package me.nathanfallet.teamcode.infrastructure.database

import java.sql.ResultSet
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords

class SqlAccountStore(private val database: Database, private val cipher: CredentialCipher) : AccountStore {
    private val merge = MergeAccountUseCase()
    override suspend fun find(identity: AccountIdentity): AccountRecord? = database.transaction { c ->
        c.rows("SELECT * FROM accounts WHERE team_id = ? AND provider = ? AND provider_account_id = ? AND organization_id = ?", identity.teamId, identity.provider, identity.accountId, identity.organizationId, map = ::accountRecord).singleOrNull()
    }
    override suspend fun upsert(verified: VerifiedProviderAccount, credentials: ProviderCredentials, expectedVersion: Long?, operationId: String): EnrollmentResult = database.transaction { c ->
        val identity = verified.identity
        c.lockTeam(identity.teamId)
        val payload = Json.encodeToString(CredentialPayload(credentials.accessToken, credentials.refreshToken, credentials.expiresAtMillis))
        val hash = Passwords.digest(listOf(identity.provider, identity.accountId, identity.organizationId, payload).joinToString("\u0000"))
        val previous = c.rows("SELECT * FROM enrollment_operations WHERE team_id = ? AND operation_id = ?", identity.teamId, operationId) { Triple(it.getString("request_hash"), it.getString("account_id"), it.getBoolean("created")) }.singleOrNull()
        if (previous != null) {
            if (previous.first != hash) return@transaction EnrollmentResult.Conflict
            val account = c.rows("SELECT * FROM accounts WHERE id = ? AND team_id = ?", previous.second, identity.teamId, map = ::accountRecord).single()
            return@transaction if (previous.third) EnrollmentResult.Created(account) else EnrollmentResult.Updated(account)
        }
        val existing = c.rows("SELECT * FROM accounts WHERE team_id = ? AND provider = ? AND provider_account_id = ? AND organization_id = ?", identity.teamId, identity.provider, identity.accountId, identity.organizationId, map = ::accountRecord).singleOrNull()
        if (existing?.credentialVersion != expectedVersion) return@transaction EnrollmentResult.Conflict
        val account = existing?.let { merge.merge(it, verified, expectedVersion!!) } ?: AccountRecord(UUID.randomUUID().toString(), identity, verified.displayName.take(120), 1, false, null, 0, AccountState.PENDING_HANDOVER)
        val envelope = cipher.encrypt(payload.toByteArray(), "${identity.teamId}:${account.id}")
        if (existing == null) c.execute("INSERT INTO accounts (id, team_id, provider, provider_account_id, organization_id, display_name, credential_version, credential_envelope, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", account.id, identity.teamId, identity.provider, identity.accountId, identity.organizationId, account.displayName, account.credentialVersion, envelope, account.state.name)
        else c.execute("UPDATE accounts SET credential_version = ?, credential_envelope = ? WHERE id = ?", account.credentialVersion, envelope, account.id)
        c.execute("INSERT INTO enrollment_operations (team_id, operation_id, request_hash, account_id, created) VALUES (?, ?, ?, ?, ?)", identity.teamId, operationId, hash, account.id, existing == null)
        if (existing == null) EnrollmentResult.Created(account) else EnrollmentResult.Updated(account)
    }
    override suspend fun list(teamId: String): List<AccountRecord> = database.transaction { it.rows("SELECT * FROM accounts WHERE team_id = ? ORDER BY display_name LIMIT 1000", teamId, map = ::accountRecord) }
    override suspend fun configure(teamId: String, id: String, displayName: String, disabled: Boolean, priority: Int, poolId: String?): AccountRecord = database.transaction { c ->
        c.lockTeam(teamId)
        if (c.execute("UPDATE accounts SET display_name = ?, disabled = ?, priority = ?, pool_id = ? WHERE team_id = ? AND id = ?", displayName, disabled, priority, poolId, teamId, id) == 0) {
            if (c.rows("SELECT id FROM accounts WHERE team_id = ? AND id = ?", teamId, id) { it.getString(1) }.isEmpty()) throw missing()
        }
        c.rows("SELECT * FROM accounts WHERE team_id = ? AND id = ?", teamId, id, map = ::accountRecord).single()
    }
    override suspend fun handover(teamId: String, id: String, accessOnly: Boolean): AccountRecord = database.transaction { c ->
        c.lockTeam(teamId)
        val account = c.rows("SELECT * FROM accounts WHERE team_id = ? AND id = ?", teamId, id, map = ::accountRecord).singleOrNull() ?: throw missing()
        val state = if (accessOnly) AccountState.ACCESS_ONLY else AccountState.READY
        c.execute("UPDATE accounts SET state = ? WHERE team_id = ? AND id = ?", state.name, teamId, id)
        account.copy(state = state)
    }
    override suspend fun credentials(teamId: String, id: String): ProviderCredentials = database.transaction { c ->
        val encrypted = c.rows("SELECT credential_envelope FROM accounts WHERE team_id = ? AND id = ?", teamId, id) { it.getString(1) }.singleOrNull() ?: throw missing()
        val value = Json.decodeFromString<CredentialPayload>(cipher.decrypt(encrypted, "$teamId:$id").decodeToString())
        ProviderCredentials(value.accessToken, value.refreshToken, value.expiresAtMillis)
    }
    private fun missing() = TeamFailure("not_found", 404, "Account not found.")

}
@Serializable
internal class CredentialPayload(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)

internal fun accountRecord(r: ResultSet) = AccountRecord(r.getString("id"), AccountIdentity(r.getString("team_id"), r.getString("provider"), r.getString("provider_account_id"), r.getString("organization_id")), r.getString("display_name"), r.getLong("credential_version"), r.getBoolean("disabled"), r.getString("pool_id"), r.getInt("priority"), AccountState.valueOf(r.getString("state")))
