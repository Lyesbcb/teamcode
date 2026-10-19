package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.ModelPolicies
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.eq

class SqlModelPolicyStore(private val database: Database) : ModelPolicyStore {
    override suspend fun get(teamId: String): ModelPolicy = database.query { readModelPolicy(teamId) }
    override suspend fun save(actor: Principal, policy: ModelPolicy): ModelPolicy {
        actor.requireAdmin()
        CheckModelPolicyUseCase().validateConfiguration(policy)
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            ModelPolicies.upsert {
                it[teamId] = actor.teamId
                it[allowedModels] = Json.encodeToString(policy.allowedModels)
                it[deniedModels] = Json.encodeToString(policy.deniedModels)
                it[allowedEfforts] = Json.encodeToString(policy.allowedEfforts)
                it[maximumThinkingTokens] = policy.maximumThinkingTokens
                it[experimentalCrossProviderTranslation] = policy.experimentalCrossProviderTranslation
            }
            audit(actor.teamId, actor.member.id, "model_policy.updated", actor.teamId)
            policy
        }
    }
}
internal fun readModelPolicy(teamId: String): ModelPolicy = ModelPolicies.selectAll().where { ModelPolicies.teamId eq teamId }
    .singleOrNull()?.let { ModelPolicy(Json.decodeFromString(it[ModelPolicies.allowedModels]), Json.decodeFromString(it[ModelPolicies.deniedModels]), Json.decodeFromString(it[ModelPolicies.allowedEfforts]), it[ModelPolicies.maximumThinkingTokens], it[ModelPolicies.experimentalCrossProviderTranslation]) } ?: ModelPolicy()
