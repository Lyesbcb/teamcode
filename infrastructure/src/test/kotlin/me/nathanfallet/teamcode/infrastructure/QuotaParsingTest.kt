package me.nathanfallet.teamcode.infrastructure

import io.ktor.http.headersOf
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.providers.CodexTransport
import kotlin.test.*

class QuotaParsingTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun claudeKeepsZeroAndSaturationWithoutInventingAReset() {
        val quotas = ClaudeTransport.parseUsage("account", json("""{
            "five_hour":{"used_percentage":null,"utilization":0,"resets_at":null},
            "seven_day":{"utilization":99},
            "seven_day_sonnet":{"resets_at":"2099-01-01T00:00:00Z"},
            "limits":[{"group":"weekly","scope":{"model":{"display_name":"Fable"}},"percent":100,"resets_at":null}]
        }"""), 1000).associateBy { it.window }
        assertEquals(setOf("five_hour", "seven_day", "model:fable"), quotas.keys)
        assertEquals(0.0, quotas.getValue("five_hour").utilization)
        assertEquals(.99, quotas.getValue("seven_day").utilization)
        assertTrue(quotas.values.all { it.resetsAtMillis == null })
        val rejected = ClaudeTransport.headerQuotas("account", headersOf("anthropic-ratelimit-unified-5h-status", "rejected"), 1000).single()
        assertEquals(1.0, rejected.utilization)
        assertNull(rejected.resetsAtMillis)
        assertTrue(ClaudeTransport.headerQuotas("account", headersOf("anthropic-ratelimit-unified-5h-reset", "3000"), 1000).isEmpty())
    }

    @Test fun claudeRecognizesNumericEpochAndPercentageAliasesButNotExpiredWindows() {
        for (reset in listOf("1700000000", "\"1700000000000\"", "\"2023-11-14T22:13:20Z\"")) {
            val quota = ClaudeTransport.parseUsage("account", json("""{"five_hour":{"usedPercentage":"42","resetAt":$reset}}"""), 1000).single()
            assertEquals(.42, quota.utilization)
            assertEquals(1_700_000_000_000L, quota.resetsAtMillis)
        }
        assertTrue(ClaudeTransport.parseUsage("account", json("""{"five_hour":{"utilization":100,"resets_at":1}}"""), 2000).isEmpty())
    }

    @Test fun codexHeaderAndUsageClassifyRoundedDurationsIdentically() {
        for ((minutes, expected) in listOf(270.0 to "five_hour", 301.5 to "five_hour", 330.0 to "five_hour", 9072.0 to "seven_day", 10079.5 to "seven_day", 11088.0 to "seven_day")) {
            val fromHeaders = CodexTransport.headerQuotas("account", headersOf(
                "x-codex-primary-used-percent" to listOf("99"), "x-codex-primary-window-minutes" to listOf(minutes.toString())), 1000).single()
            val fromUsage = CodexTransport.parseUsage("account", json("""{"rate_limits":{"secondary_window":{"usedPercentage":99,"window_seconds":${minutes * 60}}}}"""), 1000).single()
            assertEquals(expected, fromHeaders.window)
            assertEquals(fromHeaders, fromUsage)
            assertNull(fromUsage.resetsAtMillis)
        }
        for (minutes in listOf(60.0, 269.9, 330.1, 1440.0, 43200.0)) {
            assertTrue(CodexTransport.headerQuotas("account", headersOf(
                "x-codex-primary-used-percent" to listOf("100"), "x-codex-primary-window-minutes" to listOf(minutes.toString())), 1000).isEmpty())
        }
    }

    @Test fun codexRequiresRealUtilizationAndDoesNotReclassifyNamedWeeklyLimits() {
        val quotas = CodexTransport.parseUsage("account", json("""{
            "rate_limit":{"primary_window":{"used_percent":0,"limit_window_seconds":604800,"reset_at":null}},
            "additional_rate_limits":[{"limit_name":"gpt-6","primary_window":{"used_percent":98,"limit_window_seconds":18000},"secondary_window":{"used_percent":100,"limit_window_seconds":604800}}]
        }"""), 1000).associateBy { it.window }
        assertEquals(setOf("five_hour", "seven_day", "model:gpt-6:seven_day"), quotas.keys)
        assertEquals(.98, quotas.getValue("five_hour").utilization)
        assertEquals(0.0, quotas.getValue("seven_day").utilization)
        for (percent in listOf("null", "-1", "\"NaN\"", "\"Infinity\"")) {
            assertTrue(CodexTransport.parseUsage("account", json("""{"rate_limit":{"primary_window":{"used_percent":$percent,"limit_window_seconds":18000}}}"""), 1000).isEmpty())
        }
        assertTrue(CodexTransport.parseUsage("account", json("""{"rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":18000,"reset_at":1}}}"""), 2000).isEmpty())
    }
}
