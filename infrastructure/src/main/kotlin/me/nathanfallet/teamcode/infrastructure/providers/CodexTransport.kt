package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.AccountQuota
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** ChatGPT subscription transport, separate from the metered OpenAI API. */
class CodexTransport(private val http: HttpClient) : ProviderTransport {
    override val provider = "codex"
    override val protocol = WireProtocol.RESPONSES
    override suspend fun refresh(previous: ProviderCredentials): ProviderCredentials = http.preparePost("https://auth.openai.com/oauth/token") {
        contentType(ContentType.Application.Json)
        timeout { requestTimeoutMillis = 15_000 }
        setBody(buildJsonObject {
            put("grant_type", "refresh_token"); put("refresh_token", previous.refreshToken)
            put("client_id", "app_EMoamEEZ73f0CkXaXp7hrann")
        }.toString())
    }.execute { response ->
        if (response.status.value != 200) throw TeamFailure("refresh_failed", 503, "Codex refresh failed.")
        val value = response.boundedJson()
        val access = (value["access_token"] as? JsonPrimitive)?.contentOrNull
        val refresh = (value["refresh_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: previous.refreshToken
        val seconds = (value["expires_in"] as? JsonPrimitive)?.longOrNull
        if (access.isNullOrBlank() || seconds == null || seconds !in 1..31_536_000) throw TeamFailure("refresh_failed", 503, "Codex returned incomplete credentials.")
        ProviderCredentials(access, refresh, System.currentTimeMillis() + seconds * 1000)
    }
    internal suspend fun usageDocument(accountId: String, credentials: ProviderCredentials): JsonObject = http.prepareGet("https://chatgpt.com/backend-api/wham/usage") {
        bearerAuth(credentials.accessToken); header("ChatGPT-Account-Id", accountId)
        header(HttpHeaders.Accept, "application/json")
        timeout { requestTimeoutMillis = 15_000 }
    }.execute { response ->
        if (response.status.value != 200) throw TeamFailure("provider_verification_failed", 422, "Codex could not read this account's subscription usage.")
        response.boundedJson()
    }
    override suspend fun usage(account: AccountRecord, credentials: ProviderCredentials) = parseUsage(account.id, usageDocument(account.identity.organizationId, credentials), System.currentTimeMillis())
    override suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit) {
        if (body["store"] != null && body["store"] != JsonPrimitive(false)) throw TeamFailure("unsupported_storage", 400, "The Codex subscription endpoint requires store=false.")
        http.preparePost("https://chatgpt.com/backend-api/codex/responses") {
            bearerAuth(credentials.accessToken)
            header("ChatGPT-Account-Id", account.identity.organizationId)
            header(HttpHeaders.UserAgent, headers["user-agent"] ?: "TeamCode/0.1.0")
            header(HttpHeaders.Accept, "text/event-stream")
            header(HttpHeaders.AcceptEncoding, "identity")
            header("originator", "codex_cli_rs")
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 10 * 60_000; socketTimeoutMillis = 300_000 }
            setBody(JsonObject(body + ("store" to JsonPrimitive(false))).toString())
        }.execute { receive(it) }
    }
    override fun quotas(accountId: String, headers: Headers, now: Long): List<AccountQuota> = listOf("primary", "secondary").mapNotNull { key ->
        val percent = headers["x-codex-$key-used-percent"]?.toDoubleOrNull()
        val minutes = headers["x-codex-$key-window-minutes"]?.toLongOrNull()
        val reset = headers["x-codex-$key-reset-at"]?.toLongOrNull()
        bucket(accountId, percent, minutes?.times(60), reset, now)
    }
    companion object {
        fun parseUsage(accountId: String, value: JsonObject, now: Long): List<AccountQuota> = buildList {
            fun read(limit: JsonObject?, family: String? = null) {
                limit?.values?.forEach { element ->
                    val w = element as? JsonObject ?: return@forEach
                    bucket(accountId, (w["used_percent"] as? JsonPrimitive)?.doubleOrNull,
                        (w["limit_window_seconds"] as? JsonPrimitive)?.longOrNull,
                        (w["reset_at"] as? JsonPrimitive)?.longOrNull, now, family)?.let(::add)
                }
            }
            read(value["rate_limit"] as? JsonObject)
            when (val additional = value["additional_rate_limits"]) {
                is JsonArray -> additional.forEach { element ->
                    val entry = element as? JsonObject ?: return@forEach
                    val family = (entry["limit_name"] as? JsonPrimitive)?.contentOrNull ?: (entry["metered_feature"] as? JsonPrimitive)?.contentOrNull
                    if (!family.isNullOrBlank()) read(entry["rate_limit"] as? JsonObject, family.lowercase().removePrefix("codex_"))
                }
                is JsonObject -> additional.forEach { (name, entry) -> read((entry as? JsonObject)?.get("rate_limit") as? JsonObject ?: entry as? JsonObject, name.lowercase().removePrefix("codex_")) }
                else -> Unit
            }
        }
        private fun bucket(account: String, percent: Double?, seconds: Long?, reset: Long?, now: Long, family: String? = null): AccountQuota? {
            val window = when (seconds) { 18_000L -> "five_hour"; 604_800L -> "seven_day"; else -> return null }
            if (percent == null || !percent.isFinite() || percent !in 0.0..100.0 || reset == null || reset !in 1..Long.MAX_VALUE / 1000 || reset * 1000 <= now) return null
            return AccountQuota(account, if (family == null) window else "model:$family:$window", percent / 100, now, reset * 1000)
        }
    }
}
