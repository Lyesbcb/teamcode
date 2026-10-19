package me.nathanfallet.teamcode.infrastructure.database

import java.sql.Connection
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.Principal

/** Called inside admission's team-locked transaction. Contains no process-local routing state. */
internal class SqlSessionAffinity {
    fun target(c: Connection, actor: Principal, affinity: RequestAffinity, now: Long): RouteTarget? = c.rows(
        "SELECT provider, resolved_model FROM conversation_affinities WHERE team_id = ? AND member_id = ? AND conversation_key = ? AND requested_model = ? AND route_key = ? AND expires_at > ?",
        actor.teamId, actor.member.id, affinity.conversationKey, affinity.requestedModel, affinity.routeKey, now,
    ) { RouteTarget(it.getString(1), it.getString(2)) }.singleOrNull()

    fun accounts(c: Connection, actor: Principal, affinity: RequestAffinity, provider: String, model: String, now: Long): List<String> = c.rows(
        "SELECT account_id FROM conversation_affinities WHERE team_id = ? AND member_id = ? AND conversation_key = ? AND provider = ? AND expires_at > ? ORDER BY (requested_model = ? AND route_key = ? AND resolved_model = ?) DESC, last_seen_at DESC, account_id",
        actor.teamId, actor.member.id, affinity.conversationKey, provider, now, affinity.requestedModel, affinity.routeKey, model,
    ) { it.getString(1) }.distinct()

    fun loads(c: Connection, teamId: String, now: Long): Map<String, Int> = c.rows(
        "SELECT account_id, COUNT(DISTINCT member_id, conversation_key) FROM conversation_affinities WHERE team_id = ? AND last_seen_at > ? GROUP BY account_id",
        teamId, now - ACTIVE_TTL,
    ) { it.getString(1) to it.getInt(2) }.toMap()

    fun record(c: Connection, actor: Principal, affinity: RequestAffinity, target: RouteTarget, accountId: String, now: Long) {
        val updated = c.execute(
            "UPDATE conversation_affinities SET wire_protocol = ?, requested_effort = ?, thinking_tokens = ?, provider = ?, resolved_model = ?, account_id = ?, last_seen_at = ?, expires_at = ? WHERE team_id = ? AND member_id = ? AND conversation_key = ? AND requested_model = ? AND route_key = ?",
            affinity.protocol.name, affinity.effort, affinity.thinkingTokens, target.provider, target.model, accountId, now, now + KNOWN_TTL, actor.teamId, actor.member.id, affinity.conversationKey, affinity.requestedModel, affinity.routeKey,
        )
        if (updated != 0) return
        // Bound persistent hints per member and team. At capacity, admission still works without
        // recording a new hint; it never evicts another member's active conversation.
        val counts = c.rows("SELECT COUNT(*), COALESCE(SUM(member_id = ?), 0) FROM conversation_affinities WHERE team_id = ?", actor.member.id, actor.teamId) {
            it.getInt(1) to it.getInt(2)
        }.single()
        if (counts.first >= MAX_TEAM_HINTS || counts.second >= MAX_MEMBER_HINTS) return
        c.execute(
            "INSERT INTO conversation_affinities (team_id, member_id, session_key, conversation_key, requested_model, route_key, wire_protocol, requested_effort, thinking_tokens, provider, resolved_model, account_id, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE last_seen_at = ?, expires_at = ?",
            actor.teamId, actor.member.id, affinity.sessionKey, affinity.conversationKey, affinity.requestedModel, affinity.routeKey, affinity.protocol.name, affinity.effort, affinity.thinkingTokens, target.provider, target.model, accountId, now, now + KNOWN_TTL, now, now + KNOWN_TTL,
        )
    }
    companion object {
        const val KNOWN_TTL = 3_600_000L
        const val ACTIVE_TTL = 120_000L
        const val MAX_TEAM_HINTS = 10_000
        const val MAX_MEMBER_HINTS = 1_000
    }
}
