package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.CheckModelPolicyUseCase
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Admission is serialized per team; both member and physical account slots are reserved atomically. */
class SqlAdmissionStore(private val database: Database) : AdmissionStore, AdmissionQueueStore {
    private val affinities = SqlSessionAffinity()
    private val queue = SqlAdmissionQueue(database)
    override suspend fun enqueue(actor: Principal, input: AdmissionInput) = queue.enqueue(actor, input)
    override suspend fun reserveQueued(actor: Principal, ticket: AdmissionTicket, input: AdmissionInput) =
        reserveNow(actor, input.units, input.model, input.provider, input.excludedAccountIds, input.affinity, ticket, input.context, input.runId, input.workload)
    override suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean) = queue.renew(actor, ticket, blocked)
    override suspend fun release(actor: Principal, ticket: AdmissionTicket) = queue.release(actor, ticket)
    override suspend fun preferredTarget(actor: Principal, affinity: RequestAffinity): RouteTarget? = database.query {
        actor.requireScope(ServiceScope.INFERENCE); requireCurrent(this, actor)
        affinities.target(this, actor, affinity, nowMillis())
    }
    override suspend fun statistics(): AdmissionStatistics = database.query {
        val now = nowMillis()
        val active = Reservations.selectAll().where { (Reservations.teamId eq "default") and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now) }.count().toInt()
        val completed = GatewayTotals.select(GatewayTotals.completed).where { GatewayTotals.teamId eq "default" }.single()[GatewayTotals.completed]
        val queued = AdmissionWaiters.selectAll().where { (AdmissionWaiters.teamId eq "default") and (AdmissionWaiters.expiresAt greater now) }.count().toInt()
        AdmissionStatistics(active, completed, queued)
    }
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?): Reservation =
        reserveNow(actor, units, model, provider, excludedAccountIds, affinity, null)
    override suspend fun reserve(actor: Principal, input: AdmissionInput): Reservation =
        reserveNow(actor, input.units, input.model, input.provider, input.excludedAccountIds, input.affinity, null, input.context, input.runId, input.workload)
    private suspend fun reserveNow(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>, affinity: RequestAffinity?, ticket: AdmissionTicket?, context: AdmissionContext? = null, runId: String? = null, workload: Workload? = null): Reservation = database.query {
        actor.requireScope(ServiceScope.INFERENCE)
        require(units in 1..100_000_000 && excludedAccountIds.size <= 64)
        lockTeam(actor.teamId)
        val now = nowMillis()
        requireCurrent(this, actor)
        context?.let {
            val modelPolicy = readModelPolicy(actor.teamId)
            CheckModelPolicyUseCase().check(modelPolicy, it.requestedModel, model, it.effort, it.thinkingTokens)
            if (it.translated) {
                CheckModelPolicyUseCase().checkTranslation(modelPolicy)
                CheckModelPolicyUseCase().check(modelPolicy, it.requestedModel, model, it.targetEffort, null)
            }
            if (ResolveRouteUseCase().resolve(readRoutes(actor.teamId), it.requestedModel, it.protocol) != it.targets) {
                throw TeamFailure("route_changed", 409, "The configured route changed while waiting. Submit the request again.")
            }
        }
        val policy = readMemberPolicy(this, actor.member.id)
        val current = readMemberCapacity(this, policy, now, actor.teamId)
        CheckGroupAdmissionUseCase().check(current.groups, units)
        runId?.let { requireRunAdmission(actor, it, units, now, workload) }
        val scope = affinity?.sessionKey.orEmpty()
        val table = if (scope.isEmpty()) Directives else SessionDirectives
        val directive = table.selectAll().where { (table.memberId eq actor.member.id) and (table.scopeKey eq scope) and (table.expiresAt greater now) }.singleOrNull()
        if (current.activeRequests >= policy.maxConcurrent) throw TeamFailure("member_concurrency", 429, "Your concurrent request limit has been reached.")
        if (directive != null && directive[table.minInterval] > 0 && !readPacingSettings(actor.teamId).observationOnly) {
            val session = directive[table.scopeKey].takeIf { it.isNotEmpty() }
            val sessionFilter = session?.let { Reservations.sessionKey eq it } ?: Op.TRUE
            val latest = Reservations.createdAt.max()
            val last = Reservations.select(latest).where {
                (Reservations.memberId eq actor.member.id) and (Reservations.dayNumber eq now / DAY) and (Reservations.units greater 0) and sessionFilter
            }.single()[latest] ?: 0L
            if (now - last < directive[table.minInterval]) throw TeamFailure("pacing_delay", 429, "Quota burn is high. Wait briefly before the next request.")
            val active = if (session == null) current.activeRequests else Reservations.selectAll().where {
                (Reservations.memberId eq actor.member.id) and sessionFilter and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now)
            }.count().toInt()
            if (active >= directive[table.maxConcurrent]) throw TeamFailure("pacing_concurrency", 429, "This session's paced concurrent request limit has been reached.")
        }
        if (units > current.dailyAllowanceUnits - current.chargedAndReservedUnits) throw TeamFailure("member_allowance", 429, "Your daily allowance has insufficient remaining units for this request.")
        val spent = poolSpent(actor.teamId, policy.poolId.orEmpty(), now / DAY)
        if (units > current.poolDailyUnits - spent) throw TeamFailure("pool_allowance", 429, "This pool's daily team budget has been reserved or consumed.")
        val candidates = Accounts.selectAll().where {
            (Accounts.teamId eq actor.teamId) and (Accounts.disabled eq false) and (Accounts.state inList listOf("READY", "ACCESS_ONLY")) and
                (Accounts.cooldownUntil lessEq now) and (Accounts.poolId eq policy.poolId) and (Accounts.provider eq provider)
        }.map(::accountRecord)
        val usage = (QuotaWindows innerJoin Accounts).select(QuotaWindows.columns).where {
            (Accounts.teamId eq actor.teamId) and (QuotaWindows.observedAt lessEq now) and
                ((QuotaWindows.resetsAt greater now) or (QuotaWindows.resetsAt.isNull() and (QuotaWindows.observedAt greaterEq now - MAX_QUOTA_OBSERVATION_AGE_MILLIS)))
        }.map(::quotaRecord)
        val modelLimits = readQuotaModelLimits(actor.teamId)
        val count = Reservations.id.count()
        val active = Reservations.select(Reservations.accountId, count).where {
            (Reservations.teamId eq actor.teamId) and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now)
        }.groupBy(Reservations.accountId).associate { it[Reservations.accountId] to it[count].toInt() }
        val available = candidates.filter { account ->
            account.id !in excludedAccountIds && usage.none { it.accountId == account.id && it.appliesTo(model, modelLimits[account.id]?.get(model.lowercase())) && it.utilization >= .95 }
        }
        val eligible = available.filter { (active[it.id] ?: 0) < 2 }
        if (ticket != null && eligible.isEmpty() && available.isNotEmpty()) throw TeamFailure("pool_busy", 429, "Eligible accounts are currently serving other requests.")
        val pins = affinity?.let { affinities.accounts(this, actor, it, provider, model, now) }.orEmpty()
        val loads = if (affinity != null) affinities.loads(this, actor.teamId, now) else emptyMap()
        val chosen = SelectAccountUseCase().select(eligible.map { account ->
            AccountPlacement(account, active[account.id] ?: 0, loads[account.id] ?: 0,
                usage.filter { it.accountId == account.id && it.appliesTo(model, modelLimits[account.id]?.get(model.lowercase())) }.maxOfOrNull { it.utilization } ?: 0.0)
        }, pins) ?: throw TeamFailure("pool_unavailable", 429, "No eligible account has free capacity in your assigned pool.")
        ticket?.let { consumeQueueTurn(actor, it, policy, provider, now, runId) }
        affinity?.let { affinities.record(this, actor, it, RouteTarget(provider, model), chosen.id, now) }
        val id = UUID.randomUUID().toString()
        Reservations.insert {
            it[Reservations.id] = id; it[teamId] = actor.teamId; it[memberId] = actor.member.id; it[accountId] = chosen.id
            it[dayNumber] = now / DAY; it[Reservations.units] = units; it[state] = "ACTIVE"; it[expiresAt] = now + 15 * 60_000
            it[createdAt] = now; it[poolId] = policy.poolId.orEmpty(); it[sessionKey] = affinity?.sessionKey
            it[Reservations.runId] = runId
        }
        current.groups.forEach { group -> ReservationGroups.insert {
            it[reservationId] = id; it[groupId] = group.group.id; it[dayNumber] = now / DAY; it[expiresAt] = now + 15 * 60_000
        } }
        runId?.let { recordRunAdmission(it, units, affinity, provider, model, now) }
        Reservation(id, chosen, units)
    }
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) {
        require(actualUnits == null || actualUnits in 0..100_000_000)
        database.query {
            lockTeam(reservation.account.identity.teamId)
            val before = Reservations.select(Reservations.units, Reservations.runId).where { Reservations.id eq reservation.id }.singleOrNull()
            val changed = Reservations.update({ (Reservations.id eq reservation.id) and (Reservations.state eq "ACTIVE") }) {
                it[state] = if (actualUnits == null) "UNCERTAIN" else "SETTLED"; it[units] = actualUnits ?: reservation.units
            }
            if (changed == 1 && before != null) before[Reservations.runId]?.let { run ->
                AgentRuns.update({ AgentRuns.id eq run }) {
                    it[chargedUnits] = chargedUnits + ((actualUnits ?: reservation.units) - before[Reservations.units])
                    if (completed) it[AgentRuns.completed] = AgentRuns.completed + 1
                }
            }
            if (changed == 1 && completed) GatewayTotals.update({ GatewayTotals.teamId eq reservation.account.identity.teamId }) { it[GatewayTotals.completed] = GatewayTotals.completed + 1 }
        }
    }
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity = database.query {
        actor.requireScope(ServiceScope.USAGE_READ); requireCurrent(this, actor)
        if (memberId != actor.member.id) actor.requireAdmin()
        ensureMember(actor.teamId, memberId)
        readMemberCapacity(this, readMemberPolicy(this, memberId), nowMillis(), actor.teamId)
    }
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = database.query {
        actor.requireAdmin()
        if (policy.dailyUnits !in 0..100_000_000_000 || policy.weight !in 0..1000 || policy.maxConcurrent !in 1..32 || policy.poolId?.let { !Regex("[a-zA-Z0-9_-]{1,36}").matches(it) } == true) throw TeamFailure("invalid_policy", 400, "Invalid daily allowance, concurrency limit or pool identifier.")
        lockTeam(actor.teamId); requireCurrent(this, actor); ensureMember(actor.teamId, policy.memberId)
        MemberPolicies.upsert {
            it[memberId] = policy.memberId; it[dailyUnits] = policy.dailyUnits; it[maxConcurrent] = policy.maxConcurrent; it[poolId] = policy.poolId; it[weight] = policy.weight
        }
        audit(actor.teamId, actor.member.id, "policy.updated", policy.memberId)
        policy
    }
    override suspend fun poolBudgets(actor: Principal): List<PoolBudget> = database.query {
        actor.requireAdmin(); requireCurrent(this, actor)
        PoolBudgets.selectAll().where { PoolBudgets.teamId eq actor.teamId }.orderBy(PoolBudgets.poolId).map { PoolBudget(it[PoolBudgets.poolId], it[PoolBudgets.dailyUnits]) }
    }
    override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget = database.query {
        actor.requireAdmin()
        if (budget.dailyUnits !in 1..100_000_000_000 || !Regex("[a-zA-Z0-9_-]{0,36}").matches(budget.poolId)) throw TeamFailure("invalid_pool_budget", 400, "Invalid pool budget or identifier.")
        lockTeam(actor.teamId); requireCurrent(this, actor)
        PoolBudgets.upsert { it[teamId] = actor.teamId; it[poolId] = budget.poolId; it[dailyUnits] = budget.dailyUnits }
        audit(actor.teamId, actor.member.id, "pool_budget.updated", actor.teamId)
        budget
    }
    override suspend fun quotas(actor: Principal): List<AccountQuota> = database.query {
        actor.requireAdmin(); requireCurrent(this, actor)
        (QuotaWindows innerJoin Accounts).select(QuotaWindows.columns).where { Accounts.teamId eq actor.teamId }.map(::quotaRecord)
    }
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = observe(accountId, QuotaUpdate(observations, System.currentTimeMillis()))
    override suspend fun observe(accountId: String, update: QuotaUpdate) = SqlQuotaObservations(database).observe(accountId, update)
    override suspend fun isQuotaExhausted(accountId: String, model: String): Boolean = database.query {
        val team = Accounts.select(Accounts.teamId).where { Accounts.id eq accountId }.singleOrNull()?.get(Accounts.teamId) ?: return@query false
        lockTeam(team)
        val now = nowMillis()
        val limit = readQuotaModelLimits(team)[accountId]?.get(model.lowercase())
        QuotaWindows.selectAll().where { QuotaWindows.accountId eq accountId }.map(::quotaRecord)
            .any { it.isCurrent(now) && it.appliesTo(model, limit) && it.utilization >= .95 }
    }
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) {
        database.query {
            Accounts.update({ Accounts.id eq accountId }) {
                it[cooldownUntil] = Case().When(Accounts.cooldownUntil less untilMillis, longLiteral(untilMillis)).Else(Accounts.cooldownUntil)
                if (reconnect) it[state] = "RECONNECT_REQUIRED"
            }
        }
    }
    suspend fun maintain() {
        database.query {
            val now = nowMillis()
            AdmissionWaiters.deleteWhere(limit = 1000) { expiresAt lessEq now }
            ConversationAffinities.deleteWhere(limit = 1000) { expiresAt lessEq now }
            QuotaHistory.deleteWhere(limit = 1000) { observedAt less now - 7 * DAY }
            Reservations.deleteWhere(limit = 1000) { dayNumber less now / DAY - 90 }
            AgentRuns.deleteWhere(limit = 1000) {
                (heartbeatAt less now - 90 * DAY) and (lastRequestAt.isNull() or (lastRequestAt less now - 90 * DAY)) and
                    (endedAt.isNull() or (endedAt less now - 90 * DAY)) and
                    notExists(Reservations.select(Reservations.id).where { Reservations.runId eq AgentRuns.id }) and
                    notExists(AdmissionWaiters.select(AdmissionWaiters.id).where { AdmissionWaiters.runId eq AgentRuns.id })
            }
        }
    }
    private fun ensureMember(team: String, member: String) {
        if (Members.select(Members.id).where { (Members.teamId eq team) and (Members.id eq member) }.empty()) throw TeamFailure("not_found", 404, "Member not found.")
    }
    companion object { private const val DAY = 86_400_000L }
}
internal fun quotaRecord(row: ResultRow) = AccountQuota(row[QuotaWindows.accountId], row[QuotaWindows.windowName], row[QuotaWindows.utilization], row[QuotaWindows.observedAt], row[QuotaWindows.resetsAt])
