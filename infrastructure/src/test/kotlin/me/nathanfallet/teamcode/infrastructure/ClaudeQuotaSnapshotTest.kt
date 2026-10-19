package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import kotlin.test.*

class ClaudeQuotaSnapshotTest {
    private fun snapshot(payload: String, now: Long = 1000) =
        ClaudeTransport.usageSnapshot("account", Json.parseToJsonElement(payload).jsonObject, now)

    @Test fun completeEnumerationRetainsLegacyAndListedCapsIncludingUnusedFamilies() {
        val update = snapshot("""{
            "five_hour":{"utilization":15}, "seven_day":{"utilization":6},
            "seven_day_sonnet":{"utilization":70}, "seven_day_opus":null,
            "limits":[
                {"kind":"session","group":"session","percent":15,"scope":null},
                {"kind":"weekly_all","group":"weekly","percent":6,"scope":null},
                {"kind":"weekly_scoped","group":"weekly","percent":0,"resets_at":null,"scope":{"model":{"display_name":" Fable "}}},
                {"group":"weekly","percent":40,"scope":{"model":{"display_name":"Sonnet"}}},
                {"group":"weekly","percent":99,"scope":{"model":{"display_name":"Opus"}}},
                {"group":"weekly","percent":25,"scope":{"model":{"display_name":"Future_Family"}}}
            ]
        }""")
        assertTrue(update.replaceModelWindows)
        val quotas = update.windows.associateBy { it.window }
        assertEquals(setOf("five_hour", "seven_day", "model:sonnet", "model:fable", "model:opus", "model:future_family"), quotas.keys)
        assertEquals(.7, quotas.getValue("model:sonnet").utilization, "Legacy readings retain precedence")
        assertEquals(.99, quotas.getValue("model:opus").utilization, "Null legacy slots do not erase listed caps")
        assertEquals(0.0, quotas.getValue("model:fable").utilization)
        assertNull(quotas.getValue("model:fable").resetsAtMillis)
    }

    @Test fun partialOrMalformedEnumerationNeverProvesRemoval() {
        for (payload in listOf(
            "{}", """{"seven_day_opus":null}""", """{"limits":null}""", """{"limits":{}}""",
            """{"limits":[null]}""", """{"limits":[{}]}""",
            """{"limits":[{"group":123,"percent":100}]}""",
            """{"limits":[{"group":"weekly","percent":100,"scope":{"model":{"display_name":123}}}]}""",
            """{"limits":[{"group":"weekly","scope":{"model":{"display_name":"Opus"}}}]}""",
            """{"limits":[{"group":"weekly","percent":100,"scope":{"model":{}}}]}""",
            """{"limits":[{"kind":"weekly_scoped","group":"weekly","percent":100,"scope":null}]}""",
            """{"limits":[{"group":"weekly","percent":100,"scope":"bad"}]}""",
            """{"limits":[],"seven_day_opus":{}}""",
            """{"limits":[],"seven_day_opus":{"utilization":"NaN"}}""",
            """{"limits":[{"group":"weekly","percent":-1,"scope":{"model":{"display_name":"Opus"}}}]}""",
        )) assertFalse(snapshot(payload).replaceModelWindows, payload)
        val duplicate = """{"group":"weekly","percent":99,"scope":{"model":{"display_name":"Opus"}}}"""
        assertFalse(snapshot("""{"limits":[$duplicate,$duplicate]}""").replaceModelWindows)
        assertFalse(snapshot("""{"limits":[${List(129) { """{"group":"session","percent":0}""" }.joinToString(",") }]}""").replaceModelWindows)
    }

    @Test fun emptyAndExpiredEnumerationsAllowRetirementWithoutClearingSharedQuotas() {
        val empty = snapshot("""{"seven_day":{"utilization":99},"limits":[]}""")
        assertTrue(empty.replaceModelWindows)
        assertEquals("seven_day", empty.windows.single().window)
        val expired = snapshot("""{"limits":[{"group":"weekly","percent":100,"resets_at":1,"scope":{"model":{"display_name":"Opus"}}}]}""", 2000)
        assertTrue(expired.replaceModelWindows)
        assertTrue(expired.windows.isEmpty())
        val legacy = snapshot("""{"limits":[],"seven_day_sonnet":{"utilization":99}}""")
        assertTrue(legacy.replaceModelWindows)
        assertEquals("model:sonnet", legacy.windows.single().window)
    }

    @Test fun transportStampsBeforeRequestAndFailedProbesCannotProduceRemoval() = runBlocking {
        val account = AccountRecord("account", AccountIdentity("team", "anthropic", "user", "org"), "Claude", 1, false, null, 0, AccountState.ACCESS_ONLY)
        val credentials = ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE)
        var receivedAt = 0L
        HttpClient(MockEngine { request ->
            receivedAt = System.currentTimeMillis()
            assertEquals("https://api.anthropic.com/api/oauth/usage?cedar_ember=1", request.url.toString())
            assertEquals("Bearer synthetic-access", request.headers[HttpHeaders.Authorization])
            assertEquals("oauth-2025-04-20", request.headers["anthropic-beta"])
            respond("""{"limits":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout) }.use { http ->
            val update = ClaudeTransport(http).usageUpdate(account, credentials)
            assertTrue(update.replaceModelWindows)
            assertTrue(update.observedAtMillis <= receivedAt)
        }
        HttpClient(MockEngine { respond("""{"limits":[]}""", HttpStatusCode.ServiceUnavailable) }) { install(HttpTimeout) }.use { http ->
            assertEquals("usage_unavailable", assertFailsWith<TeamFailure> { ClaudeTransport(http).usageUpdate(account, credentials) }.code)
        }
    }
}
