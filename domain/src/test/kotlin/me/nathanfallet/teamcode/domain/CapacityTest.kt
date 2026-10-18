package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

class CapacityTest {
    @Test fun `concurrent requests cannot over reserve a member allowance`() = runTest {
        val ledger = AllowanceLedger()
        val admitted = (1..100).map { n -> async { ledger.reserve("member", "$n", 10, 50) } }.awaitAll()
        assertEquals(5, admitted.count { it })
        assertEquals(0L to 50L, ledger.usage("member"))
        assertTrue(ledger.reserve("other", "1", 10, 50))
    }
    @Test fun `missing usage stays reserved and duplicate settlement does not double charge`() = runTest {
        val ledger = AllowanceLedger()
        assertTrue(ledger.reserve("member", "request", 10, 20))
        assertTrue(ledger.reserve("member", "request", 10, 20))
        assertFalse(ledger.reserve("member", "request", 11, 20))
        assertFalse(ledger.settle("member", "request", null))
        assertEquals(0L to 10L, ledger.usage("member"))
        assertTrue(ledger.settle("member", "request", 4))
        assertFalse(ledger.settle("member", "request", 4))
        assertEquals(4L to 0L, ledger.usage("member"))
        assertFalse(ledger.reserve("member", "request", 10, 20))
    }
    @Test fun `reservation arithmetic cannot overflow`() = runTest {
        val ledger = AllowanceLedger()
        assertTrue(ledger.reserve("m", "r", Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(ledger.reserve("m", "s", 1, Long.MAX_VALUE))
    }
    @Test fun `fresh burn projects from observation instead of current time`() {
        val forecast = ForecastQuotaUseCase().forecast(QuotaObservation(0.5, 100_000, 200_000_000), 0.01, 150_000, 0.98)
        assertEquals(172_900_000L, forecast.reachesThresholdAtMillis)
        assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, forecast.state)
    }
    @Test fun `unknown stale zero and exhausted capacity remain distinct`() {
        val useCase = ForecastQuotaUseCase()
        val observation = QuotaObservation(0.5, 1000, 10_000_000)
        assertEquals(QuotaForecast.State.UNKNOWN, useCase.forecast(null, null, 1000).state)
        assertEquals(QuotaForecast.State.IDLE, useCase.forecast(observation, 0.0, 1000).state)
        assertEquals(QuotaForecast.State.STALE, useCase.forecast(observation, 0.1, 400_000).state)
        assertEquals(QuotaForecast.State.STALE, useCase.forecast(observation, 0.1, 0).state)
        assertEquals(QuotaForecast.State.EXHAUSTED, useCase.forecast(observation.copy(utilization = 0.95), 0.1, 1000).state)
        assertEquals(QuotaForecast.State.RESET_FIRST, useCase.forecast(observation, 0.01, 1000).state)
    }
}
