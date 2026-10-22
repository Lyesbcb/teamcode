package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules
import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules.MASK
import me.nathanfallet.teamcode.domain.cloud.PermissionAnswer
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class CloudConversationRulesTest {
    private fun masked(text: String) = CloudConversationRules.mask(text)

    @Test fun credentialsAreMasked() {
        val secrets = listOf(
            "sk-ant-api03-${"a1".repeat(20)}", "sk-proj-${"Z9".repeat(12)}", "ghp_${"A1b2".repeat(6)}", "github_pat_${"x1".repeat(15)}",
            "tcex_${"k3".repeat(10)}", "AKIA${"ABCD1234".repeat(2)}", "xoxb-1234567890-abcdef",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVl",
        )
        for (secret in secrets) assertEquals("key $MASK end", masked("key $secret end"), secret)
        assertEquals("Authorization: Bearer $MASK", masked("Authorization: Bearer abcdefghijklmnop.qrs"))
        assertEquals("token $MASK", masked("token Ab3dEf9hIjKlMn"))
        assertEquals("basic $MASK", masked("basic dXNlcjpwYXNzd29yZA=="))
        assertEquals("Authorization: Bearer $MASK", masked("Authorization: Bearer correcthorsebatterystaple"))
        assertEquals("GITHUB_TOKEN=$MASK", masked("GITHUB_TOKEN=abcdefgh1234"))
        assertEquals("{\"api_key\": $MASK}", masked("{\"api_key\": \"abcdefgh1234\"}"))
        assertEquals("https://$MASK@example.com/repo.git", masked("https://user:pa55word@example.com/repo.git"))
        assertEquals(MASK, masked("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"))
    }

    @Test fun ordinaryTextIsKept() {
        val texts = listOf(
            "task-management-feature-branch", "disk-usage-statistics-report", "etc_something_long_name_x", "max_tokens=4096",
            "token: refresh", "password: hidden", "Use a token budget of 100", "ask-for-review-before-merging-0",
            "the token management screen", "basic configuration options", "Bearer Authentication", "a token validation step",
            "Use the token configuration.", "Set up Basic Authentication.",
        )
        for (text in texts) assertEquals(text, masked(text))
    }

    @Test fun boundCutsAfterMasking() {
        val text = CloudConversationRules.bound("x".repeat(20_000))
        assertEquals(CloudConversationRules.MAX_EVENT_STRING_CHARS, text.length)
        assertTrue(text.endsWith(CloudConversationRules.TRUNCATION_MARK))
    }

    @Test fun permissionAnswersAreBounded() {
        fun valid(answer: PermissionAnswer) = CloudConversationRules.validatePermissionAnswer(answer)
        fun invalid(answer: PermissionAnswer) = assertEquals("invalid_permission_answer", assertFailsWith<TeamFailure> { valid(answer) }.code)
        valid(PermissionAnswer("9334d94c-8640-42fb-89e7-052df9b43456", true, null, mapOf("Red or blue?" to "Blue")))
        valid(PermissionAnswer("r/1+2", false, "Use echo\tinstead.\nThanks.", emptyMap()))
        invalid(PermissionAnswer("req 1", true, null, emptyMap()))
        invalid(PermissionAnswer("x".repeat(129), true, null, emptyMap()))
        invalid(PermissionAnswer("r1", false, null, mapOf("Q" to "A")))
        invalid(PermissionAnswer("r1", false, " ", emptyMap()))
        invalid(PermissionAnswer("r1", true, null, mapOf("Q" to "x".repeat(CloudConversationRules.MAX_PERMISSION_TEXT_CHARS + 1))))
        invalid(PermissionAnswer("r1", true, null, (1..17).associate { "Q$it" to "A" }))
        invalid(PermissionAnswer("r1", false, "bell\u0007", emptyMap()))
    }
}
