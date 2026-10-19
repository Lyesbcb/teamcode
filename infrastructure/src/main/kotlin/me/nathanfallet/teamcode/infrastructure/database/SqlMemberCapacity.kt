package me.nathanfallet.teamcode.infrastructure.database

import java.sql.Connection
import me.nathanfallet.teamcode.domain.capacity.*

/** Read inside the caller's transaction; admission and advice use identical weighted shares. */
internal fun readMemberPolicy(c: Connection, member: String): MemberPolicy = c.rows("SELECT * FROM member_policies WHERE member_id = ?", member) { MemberPolicy(member, it.getLong("daily_units"), it.getInt("max_concurrent"), it.getString("pool_id"), it.getInt("weight")) }.singleOrNull() ?: MemberPolicy(member)
internal fun readMemberCapacity(c: Connection, policy: MemberPolicy, now: Long, teamId: String): MemberCapacity {
    val charged = c.rows("SELECT COALESCE(SUM(units), 0) FROM reservations WHERE member_id = ? AND day_number = ?", policy.memberId, now / DAY) { it.getLong(1) }.single()
    val active = c.rows("SELECT COUNT(*) FROM reservations WHERE member_id = ? AND state = 'ACTIVE' AND expires_at > ?", policy.memberId, now) { it.getInt(1) }.single()
    val totalWeight = c.rows("SELECT COALESCE(SUM(COALESCE(p.weight, 1)), 0) FROM members m LEFT JOIN member_policies p ON p.member_id = m.id WHERE m.team_id = ? AND m.disabled = FALSE AND p.pool_id <=> ?", teamId, policy.poolId) { it.getLong(1) }.single()
    val budget = c.rows("SELECT daily_units FROM pool_budgets WHERE team_id = ? AND pool_id = ?", teamId, policy.poolId.orEmpty()) { it.getLong(1) }.singleOrNull() ?: 1_000_000
    val share = if (totalWeight == 0L) 0 else budget * policy.weight / totalWeight
    val allowance = if (policy.dailyUnits == 0L) share else minOf(share, policy.dailyUnits)
    return MemberCapacity(policy, charged, active, (now / DAY + 1) * DAY, allowance, budget, totalWeight)
}
private const val DAY = 86_400_000L
