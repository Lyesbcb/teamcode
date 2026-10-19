package me.nathanfallet.teamcode.api

import kotlin.test.*

class InferenceHeadersTest {
    @Test fun sessionHintsSurviveHopsWithoutAllowingDuplicateSpecificHeadersToFallBack() {
        val headers = mapOf("x-claude-code-session-id" to listOf("a", "b"), "session-id" to listOf("generic"), "authorization" to listOf("secret"))
        val captured = InferenceHeaders.capture(headers::get)
        assertEquals("", captured["x-claude-code-session-id"])
        assertEquals("generic", captured["session-id"])
        assertFalse("authorization" in captured)
        assertEquals(captured, InferenceHeaders.capture { captured[it]?.let(::listOf) })
    }
}
