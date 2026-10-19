package me.nathanfallet.teamcode.infrastructure.database

import java.sql.Connection
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*

class SqlRoutingStore(private val database: Database) : RoutingStore {
    override suspend fun list(teamId: String): List<RouteRule> = database.transaction { c ->
        readRoutes(c, teamId)
    }
    override suspend fun save(actor: Principal, rule: RouteRule): RouteRule {
        actor.requireAdmin()
        ResolveRouteUseCase().validate(rule)
        return database.transaction { c ->
            c.lockTeam(actor.teamId); currentAdmin(c, actor)
            val version = c.rows("SELECT version FROM model_routes WHERE team_id = ? AND id = ?", actor.teamId, rule.id) { it.getLong(1) }.singleOrNull()
            if ((version ?: 0L) != rule.version) conflict()
            if (version == null && c.rows("SELECT COUNT(*) FROM model_routes WHERE team_id = ?", actor.teamId) { it.getInt(1) }.single() >= 64) {
                throw TeamFailure("route_limit", 400, "A team can configure at most 64 routes.")
            }
            val targets = JsonArray(rule.targets.map { target -> buildJsonObject { put("provider", target.provider); target.model?.let { put("model", it) } } }).toString()
            if (version == null) c.execute("INSERT INTO model_routes (team_id, id, model_pattern, targets, priority, enabled, version) VALUES (?, ?, ?, ?, ?, ?, 1)", actor.teamId, rule.id, rule.pattern, targets, rule.priority, rule.enabled)
            else c.execute("UPDATE model_routes SET model_pattern = ?, targets = ?, priority = ?, enabled = ?, version = version + 1 WHERE team_id = ? AND id = ?", rule.pattern, targets, rule.priority, rule.enabled, actor.teamId, rule.id)
            c.audit(actor.teamId, actor.member.id, "route.saved", rule.id)
            rule.copy(version = rule.version + 1)
        }
    }
    override suspend fun delete(actor: Principal, id: String, version: Long) {
        actor.requireAdmin()
        database.transaction { c ->
            c.lockTeam(actor.teamId); currentAdmin(c, actor)
            if (c.execute("DELETE FROM model_routes WHERE team_id = ? AND id = ? AND version = ?", actor.teamId, id, version) != 1) conflict()
            c.audit(actor.teamId, actor.member.id, "route.deleted", id)
        }
    }
    private fun conflict(): Nothing = throw TeamFailure("route_conflict", 409, "This route changed. Reload it before saving or deleting.")
    private fun currentAdmin(c: Connection, actor: Principal) {
        val valid = c.rows("SELECT t.id FROM tokens t JOIN members m ON m.id = t.member_id WHERE t.id = ? AND m.id = ? AND m.team_id = ? AND m.role = 'ADMIN' AND m.disabled = FALSE AND m.must_change_password = FALSE AND t.expires_at > ?", actor.tokenId, actor.member.id, actor.teamId, c.nowMillis()) { it.getString(1) }.singleOrNull()
        if (valid == null) throw TeamFailure("authentication_required", 401, "Sign in as an administrator again.")
    }
}

internal fun readRoutes(c: Connection, teamId: String): List<RouteRule> =
    c.rows("SELECT * FROM model_routes WHERE team_id = ? ORDER BY priority DESC, id", teamId) { row ->
        val targets = Json.parseToJsonElement(row.getString("targets")).jsonArray.map { value ->
            val target = value.jsonObject
            RouteTarget(target.getValue("provider").jsonPrimitive.content, target["model"]?.jsonPrimitive?.contentOrNull)
        }
        RouteRule(row.getString("id"), row.getString("model_pattern"), targets, row.getInt("priority"), row.getBoolean("enabled"), row.getLong("version"))
    }
