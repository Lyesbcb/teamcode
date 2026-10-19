package me.nathanfallet.teamcode.domain.capacity

data class PacingRecovery(val pace: Pace, val startedAtMillis: Long)
data class PacingMemory(val advice: PacingAdvice, val expiresAtMillis: Long, val recovery: PacingRecovery?)
data class PacingDecision(val advice: PacingAdvice, val recovery: PacingRecovery? = null)

/** Persist the pending recovery so a replica restart cannot reset or skip stabilization. */
class PacingHysteresisUseCase(private val recoveryMillis: Long = 120_000) {
    fun decide(previous: PacingMemory?, candidate: PacingAdvice, now: Long): PacingDecision {
        if (previous == null || previous.expiresAtMillis <= now || previous.advice == candidate ||
            candidate.pace == Pace.UNKNOWN || rank(candidate.pace) >= rank(previous.advice.pace)) return PacingDecision(candidate)
        val recovery = previous.recovery?.takeIf { it.pace == candidate.pace && it.startedAtMillis <= now }
            ?: PacingRecovery(candidate.pace, now)
        return if (now - recovery.startedAtMillis >= recoveryMillis) PacingDecision(candidate)
            else PacingDecision(previous.advice, recovery)
    }
    private fun rank(pace: Pace) = when (pace) {
        Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1; Pace.UNKNOWN -> 2; Pace.SLOW_DOWN -> 3; Pace.PAUSE -> 4
    }
}
