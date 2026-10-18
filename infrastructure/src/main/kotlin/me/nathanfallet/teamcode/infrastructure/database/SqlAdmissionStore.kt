package me.nathanfallet.teamcode.infrastructure.database

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

/** Admission is serialized per team; both member and physical account slots are reserved atomically. */
class SqlAdmissionStore(private val database: Database) : AdmissionStore {
    override suspend fun statistics(): AdmissionStatistics = database.transaction { c ->
        val active = c.rows("SELECT COUNT(*) FROM reservations WHERE team_id = 'default' AND state = 'ACTIVE' AND expires_at > ?", c.nowMillis()) { it.getInt(1) }.single()
        val completed = c.rows("SELECT completed FROM gateway_totals WHERE team_id = 'default'") { it.getLong(1) }.single()
        AdmissionStatistics(active, completed)
    }
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String): Reservation = database.transaction { c ->
        actor.requireReady()
        require(units in 1..100_000_000)
        c.lockTeam(actor.teamId)
        val now = c.nowMillis()
        requireCurrent(c, actor)
        val policy = policy(c, actor.member.id)
        val current = capacity(c, policy, now, actor.teamId)
        val directive = c.rows("SELECT max_concurrent, min_interval FROM directives WHERE member_id = ? AND expires_at > ? AND pace IN ('SLOW_DOWN', 'PAUSE')", actor.member.id, now) { it.getInt(1) to it.getLong(2) }.singleOrNull()
        if (directive != null) {
            val last = c.rows("SELECT MAX(created_at) FROM reservations WHERE member_id = ? AND day_number = ?", actor.member.id, now / DAY) { it.getLong(1) }.single()
            if (now - last < directive.second) throw TeamFailure("pacing_delay", 429, "Quota burn is high. Wait briefly before the next request.")
        }
        if (current.activeRequests >= minOf(policy.maxConcurrent, directive?.first ?: policy.maxConcurrent)) throw TeamFailure("member_concurrency", 429, "Your concurrent request limit has been reached.")
        if (units > current.dailyAllowanceUnits - current.chargedAndReservedUnits) throw TeamFailure("member_allowance", 429, "Your daily allowance has insufficient remaining units for this request.")
        val poolSpent = c.rows("SELECT COALESCE(SUM(units), 0) FROM reservations WHERE team_id = ? AND pool_id = ? AND day_number = ?", actor.teamId, policy.poolId.orEmpty(), now / DAY) { it.getLong(1) }.single()
        if (units > current.poolDailyUnits - poolSpent) throw TeamFailure("pool_allowance", 429, "This pool's daily team budget has been reserved or consumed.")
        val candidates = c.rows("SELECT * FROM accounts WHERE team_id = ? AND disabled = FALSE AND state IN ('READY', 'ACCESS_ONLY') AND cooldown_until <= ? AND pool_id <=> ? AND provider = ?", actor.teamId, now, policy.poolId, provider, map = ::accountRecord)
        val usage = c.rows("SELECT q.* FROM quota_windows q JOIN accounts a ON a.id = q.account_id WHERE a.team_id = ? AND q.resets_at > ?", actor.teamId, now, map = ::quota)
        val active = c.rows("SELECT account_id, COUNT(*) AS n FROM reservations WHERE team_id = ? AND state = 'ACTIVE' AND expires_at > ? GROUP BY account_id", actor.teamId, now) { it.getString(1) to it.getInt(2) }.toMap()
        val eligible = candidates.filter { account ->
            (active[account.id] ?: 0) < 2 && usage.none { it.accountId == account.id && relevant(it.window, model) && it.utilization >= .95 }
        }
        val chosen = eligible.minWithOrNull(compareBy<me.nathanfallet.teamcode.domain.accounts.AccountRecord>(
            { active[it.id] ?: 0 }, { -it.priority },
            { a -> usage.filter { it.accountId == a.id && relevant(it.window, model) }.maxOfOrNull { it.utilization } ?: 0.0 },
            { it.id },
        )) ?: throw TeamFailure("pool_unavailable", 429, "No eligible account has free capacity in your assigned pool.")
        val id = UUID.randomUUID().toString()
        c.execute("INSERT INTO reservations (id, team_id, member_id, account_id, day_number, units, state, expires_at, created_at, pool_id) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)", id, actor.teamId, actor.member.id, chosen.id, now / DAY, units, now + 15 * 60_000, now, policy.poolId.orEmpty())
        Reservation(id, chosen, units)
    }
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) {
        require(actualUnits == null || actualUnits in 0..100_000_000)
        database.transaction { c ->
            c.lockTeam(reservation.account.identity.teamId)
            // Exactly-once settlement. An orphan keeps its reserved units until reconciled.
            val changed = c.execute("UPDATE reservations SET state = ?, units = ? WHERE id = ? AND state = 'ACTIVE'", if (actualUnits == null) "UNCERTAIN" else "SETTLED", actualUnits ?: reservation.units, reservation.id)
            if (changed == 1 && completed) c.execute("UPDATE gateway_totals SET completed = completed + 1 WHERE team_id = ?", reservation.account.identity.teamId)
        }
    }
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity = database.transaction { c ->
        actor.requireReady()
        requireCurrent(c, actor)
        if (memberId != actor.member.id) actor.requireAdmin()
        ensureMember(c, actor.teamId, memberId)
        capacity(c, policy(c, memberId), c.nowMillis(), actor.teamId)
    }
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = database.transaction { c ->
        actor.requireAdmin()
        if (policy.dailyUnits !in 0..100_000_000_000 || policy.weight !in 0..1000 || policy.maxConcurrent !in 1..32 || policy.poolId?.let { !Regex("[a-zA-Z0-9_-]{1,36}").matches(it) } == true) throw TeamFailure("invalid_policy", 400, "Invalid daily allowance, concurrency limit or pool identifier.")
        c.lockTeam(actor.teamId); requireCurrent(c, actor); ensureMember(c, actor.teamId, policy.memberId)
        c.execute("INSERT INTO member_policies (member_id, daily_units, max_concurrent, pool_id, weight) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE daily_units = ?, max_concurrent = ?, pool_id = ?, weight = ?", policy.memberId, policy.dailyUnits, policy.maxConcurrent, policy.poolId, policy.weight, policy.dailyUnits, policy.maxConcurrent, policy.poolId, policy.weight)
        c.audit(actor.teamId, actor.member.id, "policy.updated", policy.memberId)
        policy
    }
    override suspend fun poolBudgets(actor: Principal): List<PoolBudget> = database.transaction { c ->
        actor.requireAdmin(); requireCurrent(c, actor)
        c.rows("SELECT pool_id, daily_units FROM pool_budgets WHERE team_id = ? ORDER BY pool_id", actor.teamId) { PoolBudget(it.getString(1), it.getLong(2)) }
    }
    override suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget = database.transaction { c ->
        actor.requireAdmin()
        if (budget.dailyUnits !in 1..100_000_000_000 || !Regex("[a-zA-Z0-9_-]{0,36}").matches(budget.poolId)) throw TeamFailure("invalid_pool_budget", 400, "Invalid pool budget or identifier.")
        c.lockTeam(actor.teamId); requireCurrent(c, actor)
        c.execute("INSERT INTO pool_budgets (team_id, pool_id, daily_units) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE daily_units = ?", actor.teamId, budget.poolId, budget.dailyUnits, budget.dailyUnits)
        c.audit(actor.teamId, actor.member.id, "pool_budget.updated", actor.teamId)
        budget
    }
    override suspend fun quotas(actor: Principal): List<AccountQuota> = database.transaction { c ->
        actor.requireAdmin(); requireCurrent(c, actor)
        c.rows("SELECT q.* FROM quota_windows q JOIN accounts a ON a.id = q.account_id WHERE a.team_id = ?", actor.teamId, map = ::quota)
    }
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) {
        database.transaction { c ->
            observations.filter { it.accountId == accountId && it.window.length <= 80 && it.utilization.isFinite() && it.utilization in 0.0..1.0 && it.resetsAtMillis > it.observedAtMillis }.forEach { q ->
                c.execute("INSERT INTO quota_windows (account_id, window_name, utilization, observed_at, resets_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE utilization = IF(observed_at <= ?, ?, utilization), resets_at = IF(observed_at <= ?, ?, resets_at), observed_at = GREATEST(observed_at, ?)", q.accountId, q.window, q.utilization, q.observedAtMillis, q.resetsAtMillis, q.observedAtMillis, q.utilization, q.observedAtMillis, q.resetsAtMillis, q.observedAtMillis)
                // One sample per minute/window bounds history growth independently of inference throughput.
                c.execute("INSERT INTO quota_history (account_id, window_name, utilization, observed_at, resets_at, sample_minute) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE utilization = IF(observed_at <= ?, ?, utilization), resets_at = IF(observed_at <= ?, ?, resets_at), observed_at = GREATEST(observed_at, ?)", q.accountId, q.window, q.utilization, q.observedAtMillis, q.resetsAtMillis, q.observedAtMillis / 60_000, q.observedAtMillis, q.utilization, q.observedAtMillis, q.resetsAtMillis, q.observedAtMillis)
            }
        }
    }
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) {
        database.transaction { c ->
            c.execute("UPDATE accounts SET cooldown_until = GREATEST(cooldown_until, ?), state = CASE WHEN ? THEN 'RECONNECT_REQUIRED' ELSE state END WHERE id = ?", untilMillis, reconnect, accountId)
        }
    }
    suspend fun maintain() {
        database.transaction { c ->
            c.execute("DELETE FROM quota_history WHERE observed_at < ? LIMIT 1000", c.nowMillis() - 7 * DAY)
            c.execute("DELETE FROM reservations WHERE day_number < ? LIMIT 1000", c.nowMillis() / DAY - 90)
        }
    }
    private fun policy(c: Connection, member: String): MemberPolicy = c.rows("SELECT * FROM member_policies WHERE member_id = ?", member) { MemberPolicy(member, it.getLong("daily_units"), it.getInt("max_concurrent"), it.getString("pool_id"), it.getInt("weight")) }.singleOrNull() ?: MemberPolicy(member)
    private fun capacity(c: Connection, policy: MemberPolicy, now: Long, teamId: String): MemberCapacity {
        val charged = c.rows("SELECT COALESCE(SUM(units), 0) FROM reservations WHERE member_id = ? AND day_number = ?", policy.memberId, now / DAY) { it.getLong(1) }.single()
        val active = c.rows("SELECT COUNT(*) FROM reservations WHERE member_id = ? AND state = 'ACTIVE' AND expires_at > ?", policy.memberId, now) { it.getInt(1) }.single()
        val totalWeight = c.rows("SELECT COALESCE(SUM(COALESCE(p.weight, 1)), 0) FROM members m LEFT JOIN member_policies p ON p.member_id = m.id WHERE m.team_id = ? AND m.disabled = FALSE AND p.pool_id <=> ?", teamId, policy.poolId) { it.getLong(1) }.single()
        val budget = c.rows("SELECT daily_units FROM pool_budgets WHERE team_id = ? AND pool_id = ?", teamId, policy.poolId.orEmpty()) { it.getLong(1) }.singleOrNull() ?: 1_000_000
        val share = if (totalWeight == 0L) 0 else budget * policy.weight / totalWeight
        val allowance = if (policy.dailyUnits == 0L) share else minOf(share, policy.dailyUnits)
        return MemberCapacity(policy, charged, active, (now / DAY + 1) * DAY, allowance, budget, totalWeight)
    }
    private fun ensureMember(c: Connection, team: String, member: String) {
        if (c.rows("SELECT id FROM members WHERE team_id = ? AND id = ?", team, member) { it.getString(1) }.isEmpty()) throw TeamFailure("not_found", 404, "Member not found.")
    }
    private fun requireCurrent(c: Connection, actor: Principal) {
        val row = c.rows("SELECT m.role FROM tokens t JOIN members m ON m.id = t.member_id WHERE t.id = ? AND m.id = ? AND m.team_id = ? AND m.disabled = FALSE AND m.must_change_password = FALSE AND t.expires_at > ?", actor.tokenId, actor.member.id, actor.teamId, c.nowMillis()) { it.getString(1) }.singleOrNull()
        if (row == null || row != actor.member.role.name) throw TeamFailure("authentication_required", 401, "Sign in to TeamCode again.")
    }
    private fun quota(r: ResultSet) = AccountQuota(r.getString("account_id"), r.getString("window_name"), r.getDouble("utilization"), r.getLong("observed_at"), r.getLong("resets_at"))
    private fun relevant(window: String, model: String) = !window.startsWith("model:") || model.lowercase().contains(window.removePrefix("model:").substringBefore(':').lowercase())
    companion object { private const val DAY = 86_400_000L }
}
