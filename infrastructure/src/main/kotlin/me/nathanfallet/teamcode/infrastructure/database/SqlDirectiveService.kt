package me.nathanfallet.teamcode.infrastructure.database

import java.sql.ResultSet
import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

class SqlDirectiveService(private val database: Database) : DirectiveService {
    private val history = ForecastHistoryUseCase()
    private val pacing = PacingUseCase()
    suspend fun update() {
        database.transaction { c ->
            val now = System.currentTimeMillis()
            c.lockTeam("default")
            val accounts = c.rows("SELECT * FROM accounts WHERE team_id = 'default' AND disabled = FALSE AND state IN ('READY', 'ACCESS_ONLY') AND cooldown_until <= ?", now, map = ::accountRecord)
            val samples = c.rows("SELECT h.* FROM quota_history h JOIN accounts a ON a.id = h.account_id WHERE a.team_id = 'default' AND h.observed_at >= ? ORDER BY h.observed_at DESC LIMIT 10000", now - 3_600_000) {
                AccountQuota(it.getString("account_id"), it.getString("window_name"), it.getDouble("utilization"), it.getLong("observed_at"), it.getLong("resets_at"))
            }.groupBy { it.accountId to it.window }.mapValues { history.evaluate(it.value, now) }
            val members = c.rows("SELECT m.id, COALESCE(p.max_concurrent, 2) AS maximum, p.pool_id FROM members m LEFT JOIN member_policies p ON p.member_id = m.id WHERE m.team_id = 'default' AND m.disabled = FALSE") {
                Triple(it.getString("id"), it.getInt("maximum"), it.getString("pool_id"))
            }
            members.forEach { (member, maximum, pool) ->
                val advice = pacing.advise(accounts.filter { it.poolId == pool }.map { account -> samples.filterKeys { it.first == account.id }.values.filterNotNull() }, maximum)
                val previous = c.rows("SELECT * FROM directives WHERE member_id = ?", member, map = ::directive).singleOrNull()
                // Two-minute hysteresis; urgent exhaustion may tighten immediately. Never retain expired advice.
                val chosen = if (previous != null && previous.expiresAtMillis > now && previous.advice.pace != advice.pace && now - previous.createdAtMillis < 120_000 && advice.pace != Pace.PAUSE) previous.advice else advice
                val same = previous?.advice == chosen
                val id = if (same) previous!!.id else UUID.randomUUID().toString()
                val created = if (same) previous!!.createdAtMillis else now
                c.execute("INSERT INTO directives (member_id, id, pace, max_concurrent, min_interval, message, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = ?, pace = ?, max_concurrent = ?, min_interval = ?, message = ?, created_at = ?, expires_at = ?", member, id, chosen.pace.name, chosen.maxConcurrent, chosen.minIntervalMillis, chosen.message, created, now + 180_000, id, chosen.pace.name, chosen.maxConcurrent, chosen.minIntervalMillis, chosen.message, created, now + 180_000)
            }
            c.execute("DELETE FROM directive_receipts WHERE received_at < ? LIMIT 1000", now - 7 * 86_400_000)
        }
    }
    override suspend fun current(actor: Principal): AgentDirective? {
        actor.requireReady()
        return database.transaction { c -> c.rows("SELECT * FROM directives WHERE member_id = ? AND expires_at > ?", actor.member.id, System.currentTimeMillis(), map = ::directive).singleOrNull() }
    }
    override suspend fun acknowledge(actor: Principal, id: String, stage: String) {
        actor.requireReady()
        if (stage !in setOf("RECEIVED_BY_CLI", "EMITTED_TO_HOOK")) throw TeamFailure("invalid_stage", 400, "Unknown delivery stage.")
        database.transaction { c ->
            if (c.rows("SELECT id FROM directives WHERE member_id = ? AND id = ? AND expires_at > ?", actor.member.id, id, System.currentTimeMillis()) { it.getString(1) }.isEmpty()) throw TeamFailure("directive_expired", 409, "This directive has expired or been replaced.")
            c.execute("INSERT INTO directive_receipts (directive_id, token_id, stage, received_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE received_at = ?", id, actor.tokenId, stage, System.currentTimeMillis(), System.currentTimeMillis())
        }
    }
    private fun directive(r: ResultSet) = AgentDirective(r.getString("id"), r.getString("member_id"), PacingAdvice(Pace.valueOf(r.getString("pace")), r.getInt("max_concurrent"), r.getLong("min_interval"), r.getString("message")), r.getLong("created_at"), r.getLong("expires_at"))
}
