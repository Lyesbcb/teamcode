package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.domain.team.*

class SqlModelPolicyStore(private val database: Database) : ModelPolicyStore {
    override suspend fun get(teamId: String): ModelPolicy = database.transaction { c ->
        c.rows("SELECT * FROM model_policies WHERE team_id = ?", teamId) { ModelPolicy(Json.decodeFromString(it.getString("allowed_models")), Json.decodeFromString(it.getString("denied_models")), Json.decodeFromString(it.getString("allowed_efforts")), it.getLong("maximum_thinking_tokens")) }.singleOrNull() ?: ModelPolicy()
    }
    override suspend fun save(actor: Principal, policy: ModelPolicy): ModelPolicy {
        actor.requireAdmin()
        CheckModelPolicyUseCase().validateConfiguration(policy)
        return database.transaction { c ->
            c.lockTeam(actor.teamId)
            val allowed = Json.encodeToString(policy.allowedModels)
            val denied = Json.encodeToString(policy.deniedModels)
            val efforts = Json.encodeToString(policy.allowedEfforts)
            c.execute("INSERT INTO model_policies (team_id, allowed_models, denied_models, allowed_efforts, maximum_thinking_tokens) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE allowed_models = ?, denied_models = ?, allowed_efforts = ?, maximum_thinking_tokens = ?", actor.teamId, allowed, denied, efforts, policy.maximumThinkingTokens, allowed, denied, efforts, policy.maximumThinkingTokens)
            c.audit(actor.teamId, actor.member.id, "model_policy.updated", actor.teamId)
            policy
        }
    }
}
