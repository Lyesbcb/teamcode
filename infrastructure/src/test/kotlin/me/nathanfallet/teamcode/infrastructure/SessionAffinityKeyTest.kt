package me.nathanfallet.teamcode.infrastructure

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.RouteTarget
import me.nathanfallet.teamcode.infrastructure.gateway.SessionAffinityKey
import kotlin.test.*

class SessionAffinityKeyTest {
    private fun messages(first: String, later: String? = null) = buildJsonObject {
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "user"); put("content", first) })
            later?.let { add(buildJsonObject { put("role", "assistant"); put("content", it) }) }
        }
    }
    private fun key(body: JsonObject, session: String = "agent-1", model: String = "claude-opus-test", targets: List<RouteTarget> = listOf(RouteTarget("anthropic", model))) =
        SessionAffinityKey.from(mapOf("x-claude-code-session-id" to session), body, WireProtocol.MESSAGES, model, targets)!!

    @Test fun stableOpeningSharesAffinityWhileFreshSubagentsAndSessionsAreIndependent() {
        val first = key(messages("parent task"))
        val second = key(messages("parent task", "later response"))
        assertEquals(first, second)
        assertNotEquals(first.conversationKey, key(messages("child task")).conversationKey)
        assertEquals(first.sessionKey, key(messages("child task")).sessionKey)
        assertNotEquals(first.conversationKey, key(messages("parent task"), "different-session").conversationKey)
        assertEquals(first.conversationKey, key(messages("parent task"), model = "claude-sonnet-test").conversationKey)
        assertFalse(first.toString().contains("parent task"))
        assertFalse(first.toString().contains("agent-1"))
    }
    @Test fun headersFollowReferencePrecedenceAndBoundsWithoutRejectingRequests() {
        val body = messages("hello")
        fun from(headers: Map<String, String>) = SessionAffinityKey.from(headers, body, WireProtocol.MESSAGES, "claude-opus-test", listOf(RouteTarget("anthropic")))
        assertNull(from(emptyMap()))
        for (value in listOf("", "../other", "x".repeat(129), "a\nb", "a,b")) assertNull(from(mapOf("session-id" to value)))
        assertNotNull(from(mapOf("session-id" to "x".repeat(128))))
        assertEquals(from(mapOf("x-claude-code-session-id" to "first")), from(mapOf("x-claude-code-session-id" to "first", "session-id" to "second")))
        assertNull(from(mapOf("x-claude-code-session-id" to "invalid/id", "session-id" to "valid")))
    }
    @Test fun codexUsesTheFirstUserInputAndRouteChangesInvalidateTheTargetHint() {
        fun responses(tail: String) = Json.parseToJsonElement("""{"input":[{"role":"developer","content":"instructions"},{"role":"user","content":"task"},{"role":"assistant","content":"$tail"}]}""").jsonObject
        fun key(body: JsonObject) = SessionAffinityKey.from(mapOf("session-id" to "codex-session"), body, WireProtocol.RESPONSES, "gpt-6-sol", listOf(RouteTarget("codex", "gpt-6-sol")))!!
        assertEquals(key(responses("first")), key(responses("second")))
        val original = this.key(messages("task"))
        val changed = this.key(messages("task"), targets = listOf(RouteTarget("codex", "gpt-6-sol")))
        assertEquals(original.conversationKey, changed.conversationKey)
        assertNotEquals(original.routeKey, changed.routeKey)
    }
    @Test fun hugeOpeningsUseABoundedPrefixAndEscapedContentCannotSpoofStructure() {
        val prefix = "same ".repeat(20_000)
        assertEquals(key(messages(prefix + "one")), key(messages(prefix + "two")))
        assertNotEquals(key(messages("a\\\"},")), key(messages("a")))
    }
}
