package me.nathanfallet.teamcode.domain.policy

import me.nathanfallet.teamcode.domain.team.*

data class ModelPolicy(
    val allowedModels: List<String> = listOf("claude-*", "gpt-*"),
    val deniedModels: List<String> = listOf("*fable*"),
    val allowedEfforts: List<String> = listOf("provider-default", "low", "medium", "high", "max", "xhigh", "budgeted"),
    val maximumThinkingTokens: Long = 32_768,
)
interface ModelPolicyStore {
    suspend fun get(teamId: String): ModelPolicy
    suspend fun save(actor: Principal, policy: ModelPolicy): ModelPolicy
}
class CheckModelPolicyUseCase {
    fun validateConfiguration(policy: ModelPolicy) {
        if (policy.allowedModels.isEmpty() || (policy.allowedModels + policy.deniedModels).size > 64 ||
            (policy.allowedModels + policy.deniedModels).any { it.isBlank() || it.length > 120 || !Regex("[a-zA-Z0-9*._:/-]+").matches(it) } ||
            policy.allowedEfforts.isEmpty() || policy.allowedEfforts.any { it !in EFFORTS } || policy.maximumThinkingTokens !in 0..65_536) {
            throw TeamFailure("invalid_model_policy", 400, "Provide model patterns, recognized efforts and a thinking-token ceiling between 0 and 65536.")
        }
    }
    fun check(policy: ModelPolicy, requestedModel: String, resolvedModel: String, effort: String?, thinkingTokens: Long?) {
        validateConfiguration(policy)
        for (model in setOf(requestedModel, resolvedModel)) {
            if (policy.deniedModels.any { matchesModelPattern(it, model) } || policy.allowedModels.none { matchesModelPattern(it, model) }) {
                throw TeamFailure("model_not_allowed", 403, "This model is not allowed. Request an allowed model: ${policy.allowedModels.joinToString()}. Denied patterns: ${policy.deniedModels.joinToString()}.")
            }
        }
        val selected = effort ?: if (thinkingTokens != null) "budgeted" else "provider-default"
        if (selected !in policy.allowedEfforts) throw TeamFailure("effort_not_allowed", 403, "This reasoning effort is not allowed. Send an explicit allowed effort: ${policy.allowedEfforts.joinToString()}.")
        if (thinkingTokens != null && ("budgeted" !in policy.allowedEfforts || thinkingTokens < 0 || thinkingTokens > policy.maximumThinkingTokens)) {
            throw TeamFailure("thinking_budget_not_allowed", 403, "Explicit thinking budgets are disabled or exceed the configured thinking-token ceiling.")
        }
    }
    companion object { val EFFORTS = setOf("provider-default", "none", "minimal", "low", "medium", "high", "max", "xhigh", "budgeted") }
}
