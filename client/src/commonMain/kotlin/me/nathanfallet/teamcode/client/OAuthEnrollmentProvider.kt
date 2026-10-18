package me.nathanfallet.teamcode.client

import io.ktor.http.*
import kotlinx.serialization.json.*

/** Public-client OAuth details. No team credential is sent to these endpoints. */
interface OAuthEnrollmentProvider {
    val provider: String
    val displayName: String
    val clientId: String
    val redirectUri: String
    val tokenEndpoint: String
    val pasteInstructions: String
    fun authorizationUrl(state: String, challenge: String): String
    fun parseCode(paste: String, state: String): String
    fun exchangeBody(code: String, state: String, verifier: String): JsonObject
}

object ClaudeOAuthEnrollment : OAuthEnrollmentProvider {
    override val provider = "anthropic"
    override val displayName = "Claude"
    override val clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val redirectUri = "https://console.anthropic.com/oauth/code/callback"
    override val tokenEndpoint = "https://platform.claude.com/v1/oauth/token"
    override val pasteInstructions = "Paste the complete code#state shown by Claude."
    override fun authorizationUrl(state: String, challenge: String) = authorization(
        "https://claude.ai/oauth/authorize", state, challenge,
        "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload",
        mapOf("code" to "true"),
    )
    override fun parseCode(paste: String, state: String): String {
        require(paste.length in 1..8192) { "Invalid authorization code." }
        val parts = paste.trim().split('#', limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1] == state) { "OAuth state mismatch. Start a new sign-in attempt." }
        return parts[0]
    }
    override fun exchangeBody(code: String, state: String, verifier: String) = JsonObject(exchangeParameters(code, verifier) + ("state" to JsonPrimitive(state)))
}

object CodexOAuthEnrollment : OAuthEnrollmentProvider {
    override val provider = "codex"
    override val displayName = "Codex"
    override val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val redirectUri = "http://localhost:1455/auth/callback"
    override val tokenEndpoint = "https://auth.openai.com/oauth/token"
    override val pasteInstructions = "Paste the complete localhost:1455/auth/callback URL from the browser address bar, even if the page could not load."
    override fun authorizationUrl(state: String, challenge: String) = authorization(
        "https://auth.openai.com/oauth/authorize", state, challenge, "openid profile email offline_access",
        mapOf("id_token_add_organizations" to "true", "codex_cli_simplified_flow" to "true", "originator" to "codex_cli_rs"),
    )
    override fun parseCode(paste: String, state: String): String {
        require(paste.length in 1..8192) { "Invalid callback URL." }
        val text = paste.trim()
        val url = try { Url(if (text.startsWith("localhost:")) "http://$text" else text) }
            catch (_: Exception) { throw IllegalArgumentException("Paste the complete Codex callback URL.") }
        require(url.protocol == URLProtocol.HTTP && url.host == "localhost" && url.port == 1455 && url.encodedPath == "/auth/callback" &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty()) { "Paste the complete Codex callback URL from this sign-in attempt." }
        require(url.parameters.getAll("state") == listOf(state)) { "OAuth state mismatch. Start a new sign-in attempt." }
        require(url.parameters["error"] == null) { "Codex refused sign-in. Start a new sign-in attempt." }
        val codes = url.parameters.getAll("code")
        require(codes?.size == 1 && codes.single().isNotBlank()) { "The Codex callback has no authorization code." }
        return codes.single()
    }
    override fun exchangeBody(code: String, state: String, verifier: String) = exchangeParameters(code, verifier)
}

private fun OAuthEnrollmentProvider.authorization(endpoint: String, state: String, challenge: String, scope: String, extra: Map<String, String>): String = URLBuilder(endpoint).apply {
    parameters.apply {
        append("client_id", clientId); append("redirect_uri", redirectUri); append("response_type", "code")
        append("scope", scope); append("state", state); append("code_challenge", challenge); append("code_challenge_method", "S256")
        extra.forEach { (key, value) -> append(key, value) }
    }
}.buildString()

private fun OAuthEnrollmentProvider.exchangeParameters(code: String, verifier: String) = buildJsonObject {
    put("grant_type", "authorization_code"); put("client_id", clientId); put("redirect_uri", redirectUri)
    put("code", code); put("code_verifier", verifier)
}
