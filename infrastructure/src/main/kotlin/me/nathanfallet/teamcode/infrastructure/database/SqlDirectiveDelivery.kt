package me.nathanfallet.teamcode.infrastructure.database

import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*

/** A short delivery lease avoids concurrent duplicates without claiming the agent obeyed advice. */
internal class SqlDirectiveDelivery(private val database: Database) {
    suspend fun current(actor: Principal, sessionId: String?): AgentDirective? {
        actor.requireReady()
        val scope = sessionId?.let(::sessionHash).orEmpty()
        return database.transaction { c ->
            requireCurrent(c, actor)
            read(c, actor, scope, c.nowMillis())
        }
    }
    suspend fun claim(actor: Principal, sessionId: String, stage: String): DirectiveDelivery? {
        actor.requireReady(); validateStage(stage)
        val scope = sessionHash(sessionId)
        return database.transaction { c ->
            c.lockTeam(actor.teamId); requireCurrent(c, actor)
            val now = c.nowMillis()
            val directive = read(c, actor, scope, now) ?: return@transaction null
            val previous = c.rows("SELECT directive_id, claimed_until, emitted_at FROM directive_deliveries WHERE member_id = ? AND session_key = ? AND stage = ?", actor.member.id, scope, stage) {
                Triple(it.getString(1), it.getLong(2), it.getObject(3) != null)
            }.singleOrNull()
            if (previous?.first == directive.id && (previous.third || previous.second > now)) return@transaction null
            if (previous == null) {
                val count = c.rows("SELECT COUNT(*), COALESCE(SUM(member_id = ?), 0) FROM directive_deliveries", actor.member.id) { it.getInt(1) to it.getInt(2) }.single()
                if (count.first >= 20_000 || count.second >= 2_000) return@transaction null
            }
            val id = UUID.randomUUID().toString()
            c.execute("INSERT INTO directive_deliveries (member_id, session_key, stage, directive_id, claim_id, token_id, claimed_until, emitted_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?) ON DUPLICATE KEY UPDATE directive_id = ?, claim_id = ?, token_id = ?, claimed_until = ?, emitted_at = NULL, updated_at = ?", actor.member.id, scope, stage, directive.id, id, actor.tokenId, now + 15_000, now, directive.id, id, actor.tokenId, now + 15_000, now)
            DirectiveDelivery(directive, id)
        }
    }
    suspend fun acknowledge(actor: Principal, id: String, stage: String, sessionId: String?, claimId: String?) {
        actor.requireReady(); validateStage(stage)
        val scope = sessionId?.let(::sessionHash).orEmpty()
        if ((sessionId == null) != (claimId == null)) throw TeamFailure("invalid_claim", 400, "Session receipts require their delivery claim.")
        database.transaction { c ->
            c.lockTeam(actor.teamId); requireCurrent(c, actor)
            val now = c.nowMillis()
            val table = if (scope.isEmpty()) "directives" else "session_directives"
            if (c.rows("SELECT id FROM $table WHERE member_id = ? AND scope_key = ? AND id = ? AND expires_at > ?", actor.member.id, scope, id, now) { it.getString(1) }.isEmpty()) throw TeamFailure("directive_expired", 409, "This directive has expired or been replaced.")
            if (sessionId != null) {
                val claim = c.rows("SELECT claimed_until FROM directive_deliveries WHERE member_id = ? AND session_key = ? AND stage = ? AND directive_id = ? AND claim_id = ? AND token_id = ?", actor.member.id, scope, stage, id, claimId, actor.tokenId) { it.getLong(1) }.singleOrNull()
                if (claim == null || claim <= now) throw TeamFailure("claim_expired", 409, "This delivery claim expired or was replaced.")
                c.execute("UPDATE directive_deliveries SET emitted_at = ?, updated_at = ? WHERE member_id = ? AND session_key = ? AND stage = ?", now, now, actor.member.id, scope, stage)
            }
            c.execute("INSERT INTO directive_receipts (directive_id, token_id, stage, received_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE received_at = ?", id, actor.tokenId, stage, now, now)
        }
    }
    private fun read(c: Connection, actor: Principal, scope: String, now: Long): AgentDirective? {
        if (scope.isNotEmpty() && c.rows("SELECT 1 FROM conversation_affinities WHERE team_id = ? AND member_id = ? AND session_key = ? AND last_seen_at > ? AND expires_at > ? LIMIT 1", actor.teamId, actor.member.id, scope, now - SqlSessionAffinity.ACTIVE_TTL, now) { it.getInt(1) }.isEmpty()) return null
        val table = if (scope.isEmpty()) "directives" else "session_directives"
        return c.rows("SELECT * FROM $table WHERE member_id = ? AND scope_key = ? AND expires_at > ?", actor.member.id, scope, now, map = ::directiveRecord).singleOrNull()
    }
    private fun validateStage(stage: String) {
        if (stage !in setOf("RECEIVED_BY_CLI", "EMITTED_TO_HOOK")) throw TeamFailure("invalid_stage", 400, "Unknown delivery stage.")
    }
    private fun sessionHash(value: String): String {
        if (!Regex("[A-Za-z0-9._-]{1,128}").matches(value)) throw TeamFailure("invalid_session", 400, "Provide a valid client session identifier.")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
