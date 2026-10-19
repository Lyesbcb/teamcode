package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class SqlAccountStore(private val database: Database, private val cipher: CredentialCipher) : AccountStore {
    private val merge = MergeAccountUseCase()
    private fun identityFilter(identity: AccountIdentity) = (Accounts.teamId eq identity.teamId) and (Accounts.provider eq identity.provider) and
        (Accounts.providerAccountId eq identity.accountId) and (Accounts.organizationId eq identity.organizationId)
    override suspend fun find(identity: AccountIdentity): AccountRecord? = database.query {
        Accounts.selectAll().where { identityFilter(identity) }.singleOrNull()?.let(::accountRecord)
    }
    override suspend fun upsert(verified: VerifiedProviderAccount, credentials: ProviderCredentials, expectedVersion: Long?, operationId: String): EnrollmentResult = database.query {
        val identity = verified.identity
        lockTeam(identity.teamId)
        val payload = Json.encodeToString(CredentialPayload(credentials.accessToken, credentials.refreshToken, credentials.expiresAtMillis))
        val hash = Passwords.digest(listOf(identity.provider, identity.accountId, identity.organizationId, payload).joinToString("\u0000"))
        val previous = EnrollmentOperations.selectAll().where { (EnrollmentOperations.teamId eq identity.teamId) and (EnrollmentOperations.operationId eq operationId) }.singleOrNull()
        if (previous != null) {
            if (previous[EnrollmentOperations.requestHash] != hash) return@query EnrollmentResult.Conflict
            val account = Accounts.selectAll().where { (Accounts.id eq previous[EnrollmentOperations.accountId]) and (Accounts.teamId eq identity.teamId) }.single().let(::accountRecord)
            return@query if (previous[EnrollmentOperations.created]) EnrollmentResult.Created(account) else EnrollmentResult.Updated(account)
        }
        val row = Accounts.selectAll().where { identityFilter(identity) }.forUpdate().singleOrNull()
        val existing = row?.let(::accountRecord)
        if (row != null && row[Accounts.refreshPending] && existing!!.state != AccountState.RECONNECT_REQUIRED && row[Accounts.refreshStartedAt] + 60_000 > nowMillis()) return@query EnrollmentResult.Conflict
        if (existing?.credentialVersion != expectedVersion) return@query EnrollmentResult.Conflict
        val account = existing?.let { merge.merge(it, verified, expectedVersion!!) } ?: AccountRecord(UUID.randomUUID().toString(), identity, verified.displayName.take(120), 1, false, null, 0, AccountState.PENDING_HANDOVER)
        val envelope = cipher.encrypt(payload.toByteArray(), "${identity.teamId}:${account.id}")
        if (existing == null) Accounts.insert {
            it[id] = account.id; it[teamId] = identity.teamId; it[provider] = identity.provider; it[providerAccountId] = identity.accountId
            it[organizationId] = identity.organizationId; it[displayName] = account.displayName; it[credentialVersion] = account.credentialVersion
            it[credentialEnvelope] = envelope; it[state] = account.state.name
        } else Accounts.update({ Accounts.id eq account.id }) { it[credentialVersion] = account.credentialVersion; it[credentialEnvelope] = envelope; it[refreshPending] = false }
        EnrollmentOperations.insert { it[teamId] = identity.teamId; it[EnrollmentOperations.operationId] = operationId; it[requestHash] = hash; it[accountId] = account.id; it[created] = existing == null }
        if (existing == null) EnrollmentResult.Created(account) else EnrollmentResult.Updated(account)
    }
    override suspend fun list(teamId: String): List<AccountRecord> = database.query { Accounts.selectAll().where { Accounts.teamId eq teamId }.orderBy(Accounts.displayName).limit(1000).map(::accountRecord) }
    override suspend fun configure(teamId: String, id: String, displayName: String, disabled: Boolean, priority: Int, poolId: String?): AccountRecord = database.query {
        lockTeam(teamId)
        Accounts.update({ (Accounts.teamId eq teamId) and (Accounts.id eq id) }) {
            it[Accounts.displayName] = displayName; it[Accounts.disabled] = disabled; it[Accounts.priority] = priority; it[Accounts.poolId] = poolId
        }
        Accounts.selectAll().where { (Accounts.teamId eq teamId) and (Accounts.id eq id) }.singleOrNull()?.let(::accountRecord) ?: throw missing()
    }
    override suspend fun handover(teamId: String, id: String, accessOnly: Boolean): AccountRecord = database.query {
        lockTeam(teamId)
        val row = Accounts.selectAll().where { (Accounts.teamId eq teamId) and (Accounts.id eq id) }.forUpdate().singleOrNull() ?: throw missing()
        if (row[Accounts.refreshPending]) throw TeamFailure("provider_reconnect", 409, "Import verified current credentials before changing refresh ownership.")
        val state = if (accessOnly) AccountState.ACCESS_ONLY else AccountState.READY
        Accounts.update({ (Accounts.teamId eq teamId) and (Accounts.id eq id) }) { it[Accounts.state] = state.name }
        accountRecord(row).copy(state = state)
    }
    override suspend fun credentials(teamId: String, id: String): ProviderCredentials = database.query {
        val encrypted = Accounts.select(Accounts.credentialEnvelope).where { (Accounts.teamId eq teamId) and (Accounts.id eq id) }.singleOrNull()?.get(Accounts.credentialEnvelope) ?: throw missing()
        val value = Json.decodeFromString<CredentialPayload>(cipher.decrypt(encrypted, "$teamId:$id").decodeToString())
        ProviderCredentials(value.accessToken, value.refreshToken, value.expiresAtMillis)
    }
    private fun missing() = TeamFailure("not_found", 404, "Account not found.")
}
@Serializable
internal class CredentialPayload(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)
internal fun accountRecord(row: ResultRow) = AccountRecord(row[Accounts.id], AccountIdentity(row[Accounts.teamId], row[Accounts.provider], row[Accounts.providerAccountId], row[Accounts.organizationId]), row[Accounts.displayName], row[Accounts.credentialVersion], row[Accounts.disabled], row[Accounts.poolId], row[Accounts.priority], AccountState.valueOf(row[Accounts.state]))
