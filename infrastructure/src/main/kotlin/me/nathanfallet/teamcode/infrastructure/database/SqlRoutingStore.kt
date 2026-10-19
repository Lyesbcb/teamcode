package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.ModelRoutes
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class SqlRoutingStore(private val database: Database) : RoutingStore {
    override suspend fun list(teamId: String): List<RouteRule> = database.query { readRoutes(teamId) }
    override suspend fun save(actor: Principal, rule: RouteRule): RouteRule {
        actor.requireAdmin()
        ResolveRouteUseCase().validate(rule)
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            val version = ModelRoutes.select(ModelRoutes.version).where { (ModelRoutes.teamId eq actor.teamId) and (ModelRoutes.id eq rule.id) }.singleOrNull()?.get(ModelRoutes.version)
            if ((version ?: 0L) != rule.version) conflict()
            if (version == null && ModelRoutes.selectAll().where { ModelRoutes.teamId eq actor.teamId }.count() >= 64) {
                throw TeamFailure("route_limit", 400, "A team can configure at most 64 routes.")
            }
            val targets = JsonArray(rule.targets.map { target -> buildJsonObject { put("provider", target.provider); target.model?.let { put("model", it) } } }).toString()
            ModelRoutes.upsert {
                it[teamId] = actor.teamId; it[id] = rule.id; it[modelPattern] = rule.pattern; it[ModelRoutes.targets] = targets
                it[priority] = rule.priority; it[enabled] = rule.enabled; it[ModelRoutes.version] = rule.version + 1
            }
            audit(actor.teamId, actor.member.id, "route.saved", rule.id)
            rule.copy(version = rule.version + 1)
        }
    }
    override suspend fun delete(actor: Principal, id: String, version: Long) {
        actor.requireAdmin()
        database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            if (ModelRoutes.deleteWhere { (teamId eq actor.teamId) and (ModelRoutes.id eq id) and (ModelRoutes.version eq version) } != 1) conflict()
            audit(actor.teamId, actor.member.id, "route.deleted", id)
        }
    }
    private fun conflict(): Nothing = throw TeamFailure("route_conflict", 409, "This route changed. Reload it before saving or deleting.")
}
internal fun readRoutes(teamId: String): List<RouteRule> = ModelRoutes.selectAll().where { ModelRoutes.teamId eq teamId }
    .orderBy(ModelRoutes.priority to SortOrder.DESC, ModelRoutes.id to SortOrder.ASC).map { row ->
        val targets = Json.parseToJsonElement(row[ModelRoutes.targets]).jsonArray.map { value ->
            val target = value.jsonObject
            RouteTarget(target.getValue("provider").jsonPrimitive.content, target["model"]?.jsonPrimitive?.contentOrNull)
        }
        RouteRule(row[ModelRoutes.id], row[ModelRoutes.modelPattern], targets, row[ModelRoutes.priority], row[ModelRoutes.enabled], row[ModelRoutes.version])
    }
