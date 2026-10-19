package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class ModelPolicyView(val allowedModels: List<String>, val deniedModels: List<String>, val allowedEfforts: List<String>, val maximumThinkingTokens: Long, val experimentalCrossProviderTranslation: Boolean = false)
