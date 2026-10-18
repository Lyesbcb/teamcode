package me.nathanfallet.teamcode.domain.capacity

/** An observation is one account/window; percentages from different plans are never summed. */
data class QuotaObservation(val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long)
data class QuotaForecast(val reachesThresholdAtMillis: Long?, val pointsPerHour: Double?, val state: State) {
    enum class State { UNKNOWN, STALE, EXHAUSTED, IDLE, RESET_FIRST, EXHAUSTS_FIRST }
}

class ForecastQuotaUseCase(private val maximumAgeMillis: Long = 300_000) {
    fun forecast(observation: QuotaObservation?, ratePerHour: Double?, nowMillis: Long, threshold: Double = 0.9): QuotaForecast {
        require(threshold.isFinite() && threshold > 0 && threshold <= 1)
        if (observation == null || ratePerHour == null || !ratePerHour.isFinite() || ratePerHour < 0 ||
            !observation.utilization.isFinite() || observation.utilization !in 0.0..1.0) {
            return QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)
        }
        if (observation.observedAtMillis > nowMillis || observation.resetsAtMillis <= nowMillis ||
            nowMillis - observation.observedAtMillis > maximumAgeMillis) {
            return QuotaForecast(null, ratePerHour * 100, QuotaForecast.State.STALE)
        }
        if (observation.utilization >= threshold) return QuotaForecast(nowMillis, ratePerHour * 100, QuotaForecast.State.EXHAUSTED)
        if (ratePerHour == 0.0) return QuotaForecast(null, 0.0, QuotaForecast.State.IDLE)
        val eta = observation.observedAtMillis + ((threshold - observation.utilization) / ratePerHour * 3_600_000).toLong()
        return QuotaForecast(eta, ratePerHour * 100,
            if (eta < observation.resetsAtMillis) QuotaForecast.State.EXHAUSTS_FIRST else QuotaForecast.State.RESET_FIRST)
    }
}
