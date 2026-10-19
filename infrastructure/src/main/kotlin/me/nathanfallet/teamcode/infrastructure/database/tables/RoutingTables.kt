package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ModelPolicies : Table("model_policies") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "model_policies_ibfk_1")
    val allowedModels = text("allowed_models")
    val deniedModels = text("denied_models")
    val allowedEfforts = text("allowed_efforts")
    val maximumThinkingTokens = long("maximum_thinking_tokens")
    val experimentalCrossProviderTranslation = bool("experimental_cross_provider_translation").default(false)
    override val primaryKey = PrimaryKey(teamId)
}
object ModelRoutes : Table("model_routes") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "model_routes_ibfk_1")
    val id = varchar("id", 36)
    val modelPattern = varchar("model_pattern", 120)
    val targets = text("targets")
    val priority = integer("priority").default(0)
    val enabled = bool("enabled").default(true)
    val version = long("version")
    override val primaryKey = PrimaryKey(teamId, id)
}
object ConversationAffinities : Table("conversation_affinities") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "conversation_affinities_ibfk_1")
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "conversation_affinities_ibfk_2")
    val sessionKey = char("session_key", 64, "ascii_bin")
    val conversationKey = char("conversation_key", 64, "ascii_bin")
    val requestedModel = varchar("requested_model", 120, "ascii_bin")
    val routeKey = char("route_key", 64, "ascii_bin")
    val provider = varchar("provider", 32)
    val resolvedModel = varchar("resolved_model", 120)
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "conversation_affinities_ibfk_3")
    val lastSeenAt = long("last_seen_at")
    val expiresAt = long("expires_at")
    val wireProtocol = varchar("wire_protocol", 20).nullable()
    val requestedEffort = varchar("requested_effort", 32).nullable()
    val thinkingTokens = long("thinking_tokens").nullable()
    override val primaryKey = PrimaryKey(teamId, memberId, conversationKey, requestedModel, routeKey)
    init {
        index("affinity_expiry", false, expiresAt)
        index("affinity_load", false, teamId, lastSeenAt, accountId)
    }
}
