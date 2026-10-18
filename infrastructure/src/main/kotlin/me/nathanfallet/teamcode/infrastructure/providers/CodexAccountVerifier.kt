package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import java.util.Base64
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** JWT claims only select an account header. The authenticated provider response establishes identity. */
class CodexAccountVerifier(http: HttpClient) : AccountVerifier {
    private val transport = CodexTransport(http)
    override suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount {
        val hint = try {
            val payload = credentials.accessToken.split('.').takeIf { it.size == 3 }?.get(1) ?: error("jwt")
            val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()).jsonObject
            ((claims["https://api.openai.com/auth"] as? JsonObject)?.get("chatgpt_account_id") as? JsonPrimitive)?.contentOrNull
        } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }
        if (hint.isNullOrBlank() || !Regex("[a-zA-Z0-9_-]{1,128}").matches(hint)) throw invalid()
        val profile = transport.usageDocument(hint, credentials)
        val account = (profile["account_id"] as? JsonPrimitive)?.contentOrNull
        val user = (profile["user_id"] as? JsonPrimitive)?.contentOrNull
        if (account != hint || user.isNullOrBlank() || !Regex("[a-zA-Z0-9_-]{1,128}").matches(user)) throw invalid()
        val plan = (profile["plan_type"] as? JsonPrimitive)?.contentOrNull?.filter { it.isLetterOrDigit() }?.take(32).orEmpty()
        return VerifiedProviderAccount(AccountIdentity(teamId, "codex", user, account), "Codex ${plan.ifEmpty { "subscription" }}")
    }
    private fun invalid() = TeamFailure("provider_verification_failed", 422, "Codex could not verify this account identity. Reconnect the account.")
}
