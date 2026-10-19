package me.nathanfallet.teamcode.infrastructure.database

import java.sql.ResultSet
import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.policy.CheckModelPolicyUseCase
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.infrastructure.routing.routeFingerprint
import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

class SqlDirectiveService(private val database: Database) : DirectiveService {
    private val delivery = SqlDirectiveDelivery(database)
    private val history = ForecastHistoryUseCase()
    private val pacing = RoutePacingUseCase()
    private val hysteresis = PacingHysteresisUseCase()
    suspend fun update() {
        database.transaction { c ->
            c.lockTeam("default")
            val now = c.nowMillis()
            val accounts = c.rows("SELECT * FROM accounts WHERE team_id = 'default' AND disabled = FALSE AND state IN ('READY', 'ACCESS_ONLY') AND cooldown_until <= ?", now, map = ::accountRecord)
            val samples = c.rows("SELECT h.* FROM quota_history h JOIN accounts a ON a.id = h.account_id WHERE a.team_id = 'default' AND h.observed_at >= ? ORDER BY h.observed_at DESC LIMIT 10000", now - 3_600_000) {
                AccountQuota(it.getString("account_id"), it.getString("window_name"), it.getDouble("utilization"), it.getLong("observed_at"), it.getLong("resets_at"))
            }.groupBy { it.accountId to it.window }.mapValues { history.evaluate(it.value, now) }
            val members = c.rows("SELECT m.id, COALESCE(p.max_concurrent, 2) AS maximum, p.pool_id FROM members m LEFT JOIN member_policies p ON p.member_id = m.id WHERE m.team_id = 'default' AND m.disabled = FALSE") {
                Triple(it.getString("id"), it.getInt("maximum"), it.getString("pool_id"))
            }
            val routes = readRoutes(c, "default")
            val modelPolicy = readModelPolicy(c, "default")
            val contexts = c.rows("SELECT member_id, session_key, requested_model, provider, resolved_model, route_key, wire_protocol, requested_effort, thinking_tokens FROM conversation_affinities WHERE team_id = 'default' AND last_seen_at > ? AND expires_at > ?", now - SqlSessionAffinity.ACTIVE_TTL, now) {
                it.getString("member_id") to Context(it.getString("session_key"), it.getString("requested_model"), RouteTarget(it.getString("provider"), it.getString("resolved_model")), it.getString("route_key"), it.getString("wire_protocol"), it.getString("requested_effort"), it.getLong("thinking_tokens").let { value -> if (it.wasNull()) null else value })
            }.groupBy({ it.first }, { it.second })
            members.forEach { (member, maximum, pool) ->
                val memberContexts = contexts[member].orEmpty()
                val scopes = mapOf("" to memberContexts) + memberContexts.groupBy { it.sessionKey }
                val accessible = accounts.filter { it.poolId == pool }
                val accountPacing = accessible.map { account -> AccountPacing(account, samples.filterKeys { it.first == account.id }.values.filterNotNull()) }
                val capacity = readMemberCapacity(c, readMemberPolicy(c, member), now, "default")
                val poolSpent = c.rows("SELECT COALESCE(SUM(units), 0) FROM reservations WHERE team_id = 'default' AND pool_id = ? AND day_number = ?", pool.orEmpty(), now / 86_400_000) { it.getLong(1) }.single()
                val allowanceAvailable = capacity.dailyAllowanceUnits > capacity.chargedAndReservedUnits && capacity.poolDailyUnits > poolSpent
                scopes.forEach { (scopeKey, scoped) ->
                    val table = if (scopeKey.isEmpty()) "directives" else "session_directives"
                    val active = scoped.distinct()
                    val valid = active.all { context ->
                        val protocol = WireProtocol.entries.firstOrNull { it.name == context.protocol }
                        if (protocol == null) false else {
                            val targets = ResolveRouteUseCase().resolve(routes, context.requested, protocol)
                            val permitted = try {
                                CheckModelPolicyUseCase().check(modelPolicy, context.requested, context.target.model!!, context.effort, context.thinkingTokens)
                                true
                            } catch (_: TeamFailure) { false }
                            permitted && context.target in targets && context.routeKey == routeFingerprint(protocol, context.requested, targets)
                        }
                    }
                    val targets = if (valid) active.map { it.target }.toSet() else emptySet()
                    val advice = pacing.advise(targets, accountPacing, maximum, now, allowanceAvailable)
                    // A different route, policy, pool, priority or eligible account set must not
                    // inherit optimistic advice (or an old throttle) from another capacity context.
                    val scope = listOf(maximum.toString(), pool.orEmpty(), modelPolicy.toString(), valid.toString(), allowanceAvailable.toString(),
                        active.map { it.toString() }.sorted().joinToString("\n"),
                        accessible.map { "${it.id}:${it.priority}" }.sorted().joinToString("\n")).joinToString("\n")
                    val key = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
                    val previous = c.rows("SELECT * FROM $table WHERE member_id = ? AND scope_key = ?", member, scopeKey) { row ->
                        val old = directiveRecord(row)
                        val recovery = row.getString("recovery_pace")?.let { PacingRecovery(Pace.valueOf(it), row.getLong("recovery_started_at")) }
                        Stored(old, row.getString("context_key"), PacingMemory(old.advice, old.expiresAtMillis, recovery))
                    }.singleOrNull()
                    val decision = hysteresis.decide(previous?.takeIf { it.key == key }?.memory, advice, now)
                    val chosen = decision.advice
                    val old = previous?.directive
                    val same = old?.advice == chosen && previous.key == key
                    val id = if (same) old.id else UUID.randomUUID().toString()
                    val created = if (same) old.createdAtMillis else now
                    val recovery = decision.recovery
                    c.execute("INSERT INTO $table (member_id, scope_key, id, pace, max_concurrent, min_interval, message, created_at, expires_at, context_key, recovery_pace, recovery_started_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = ?, pace = ?, max_concurrent = ?, min_interval = ?, message = ?, created_at = ?, expires_at = ?, context_key = ?, recovery_pace = ?, recovery_started_at = ?", member, scopeKey, id, chosen.pace.name, chosen.maxConcurrent, chosen.minIntervalMillis, chosen.message, created, now + 180_000, key, recovery?.pace?.name, recovery?.startedAtMillis, id, chosen.pace.name, chosen.maxConcurrent, chosen.minIntervalMillis, chosen.message, created, now + 180_000, key, recovery?.pace?.name, recovery?.startedAtMillis)
                }
            }
            c.execute("DELETE FROM session_directives WHERE expires_at <= ? LIMIT 1000", now)
            c.execute("DELETE FROM directive_deliveries WHERE updated_at < ? OR NOT EXISTS (SELECT 1 FROM session_directives d WHERE d.member_id = directive_deliveries.member_id AND d.scope_key = directive_deliveries.session_key AND d.expires_at > ?) LIMIT 1000", now - 7 * 86_400_000, now)
            c.execute("DELETE FROM directive_receipts WHERE received_at < ? LIMIT 1000", now - 7 * 86_400_000)
        }
    }
    override suspend fun current(actor: Principal, sessionId: String?): AgentDirective? = delivery.current(actor, sessionId)
    override suspend fun claim(actor: Principal, sessionId: String, stage: String): DirectiveDelivery? = delivery.claim(actor, sessionId, stage)
    override suspend fun acknowledge(actor: Principal, id: String, stage: String, sessionId: String?, claimId: String?) = delivery.acknowledge(actor, id, stage, sessionId, claimId)
    private data class Stored(val directive: AgentDirective, val key: String?, val memory: PacingMemory)
    private data class Context(val sessionKey: String, val requested: String, val target: RouteTarget, val routeKey: String, val protocol: String?, val effort: String?, val thinkingTokens: Long?)

}

internal fun directiveRecord(r: ResultSet) = AgentDirective(r.getString("id"), r.getString("member_id"), PacingAdvice(Pace.valueOf(r.getString("pace")), r.getInt("max_concurrent"), r.getLong("min_interval"), r.getString("message")), r.getLong("created_at"), r.getLong("expires_at"))
