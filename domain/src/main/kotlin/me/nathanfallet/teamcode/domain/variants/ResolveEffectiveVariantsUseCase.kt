package me.nathanfallet.teamcode.domain.variants

/** One environment variable the member's execution receives. */
data class EffectiveEnvironmentVariable(val name: String, val variantId: String, val variantName: String, val version: Int, val value: SecretValue)

/** One MCP server the member may discover and call through the gateway. [credential] is null for a server without one. */
data class EffectiveMcpServer(
    val name: String, val variantId: String, val variantName: String, val version: Int,
    val endpoint: String, val config: Map<String, String>, val credential: SecretValue?,
)

data class EffectiveVariants(val memberId: String, val environment: List<EffectiveEnvironmentVariable>, val mcpServers: List<EffectiveMcpServer>) {
    companion object { fun none(memberId: String) = EffectiveVariants(memberId, emptyList(), emptyList()) }
}

/** A candidate selection for one slot, with its decrypted values. Adapters return every source they know about. */
data class AssignmentCandidate(
    val target: VariantTarget, val source: AssignmentSource, val variant: Variant,
    val sharedSecret: SecretValue?, val memberSecret: SecretValue?,
)

/** Current persisted state for one member. [eligible] is false for a disabled member or one outside the team. */
data class MemberVariantState(val eligible: Boolean, val candidates: List<AssignmentCandidate>, val exclusions: Set<VariantTarget>)

/** Persistence port read on every resolution, so a change made on any replica is visible to the next call. */
interface EffectiveVariantSource {
    suspend fun state(teamId: String, memberId: String): MemberVariantState
}

/** Pure precedence rules, kept separate so group assignments can be added without touching the no-assignment rule. */
object VariantResolution {
    /**
     * An exclusion always wins. Otherwise the candidate with the highest source precedence fills the slot; a slot
     * without any candidate stays empty. Shared variants are never applied implicitly.
     */
    fun select(candidates: List<AssignmentCandidate>, exclusions: Set<VariantTarget>): List<AssignmentCandidate> =
        candidates.filter { it.target !in exclusions && it.target == it.variant.slot }
            .groupBy { it.target }
            .map { (_, options) -> options.maxWith(compareBy<AssignmentCandidate> { it.source.precedence }.thenBy { it.variant.id }) }
            .sortedWith(compareBy({ it.target.kind }, { it.target.name }))
}

/**
 * Resolves what a member effectively receives. This is the trusted server-side entry point for the MCP gateway and
 * cloud sessions: it returns decrypted values, so callers must have authenticated the member (or be acting on an
 * authorized session for them) and must never log or return its result to another member.
 */
class ResolveEffectiveVariantsUseCase(private val source: EffectiveVariantSource) {
    suspend operator fun invoke(teamId: String, memberId: String): EffectiveVariants {
        val state = source.state(teamId, memberId)
        if (!state.eligible) return EffectiveVariants.none(memberId)
        val selected = VariantResolution.select(state.candidates, state.exclusions)
        val environment = selected.filter { it.target.kind == VariantKind.ENV }.mapNotNull { candidate ->
            val value = candidate.valueFor() ?: return@mapNotNull null
            EffectiveEnvironmentVariable(candidate.target.name, candidate.variant.id, candidate.variant.name, candidate.variant.version, value)
        }
        val servers = selected.filter { it.target.kind == VariantKind.MCP }.mapNotNull { candidate ->
            val endpoint = candidate.variant.endpoint ?: return@mapNotNull null
            if (candidate.variant.binding == VariantBinding.PER_MEMBER && candidate.memberSecret == null) return@mapNotNull null
            EffectiveMcpServer(candidate.target.name, candidate.variant.id, candidate.variant.name, candidate.variant.version, endpoint, candidate.variant.config, candidate.valueFor())
        }
        return EffectiveVariants(memberId, environment, servers)
    }
    private fun AssignmentCandidate.valueFor() = if (variant.binding == VariantBinding.PER_MEMBER) memberSecret else sharedSecret
}
