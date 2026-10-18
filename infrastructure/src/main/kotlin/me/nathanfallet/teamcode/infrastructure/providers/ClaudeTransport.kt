package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.AccountQuota
import me.nathanfallet.teamcode.domain.team.TeamFailure
import java.time.Instant

class ClaudeTransport(private val http: HttpClient) : ProviderTransport {
    override val provider = "anthropic"
    override val protocol = me.nathanfallet.teamcode.domain.gateway.WireProtocol.MESSAGES
    override fun quotas(accountId: String, headers: Headers, now: Long) = headerQuotas(accountId, headers, now)
    override suspend fun refresh(previous: ProviderCredentials): ProviderCredentials = http.preparePost("https://platform.claude.com/v1/oauth/token") {
        contentType(ContentType.Application.Json)
        timeout { requestTimeoutMillis = 15_000 }
        setBody(buildJsonObject {
            put("grant_type", "refresh_token"); put("refresh_token", previous.refreshToken)
            put("client_id", "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
        }.toString())
    }.execute { response ->
        if (response.status.value != 200) throw TeamFailure("refresh_failed", 503, "Provider refresh failed.")
        val value = response.boundedJson()
        val access = (value["access_token"] as? JsonPrimitive)?.contentOrNull
        val refresh = (value["refresh_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: previous.refreshToken
        val seconds = (value["expires_in"] as? JsonPrimitive)?.longOrNull
        if (access.isNullOrBlank() || seconds == null || seconds !in 1..31_536_000) throw TeamFailure("refresh_failed", 503, "Provider refresh returned incomplete credentials.")
        ProviderCredentials(access, refresh, System.currentTimeMillis() + seconds * 1000)
    }
    override suspend fun usage(account: AccountRecord, credentials: ProviderCredentials): List<AccountQuota> = http.prepareGet("https://api.anthropic.com/api/oauth/usage?cedar_ember=1") {
        bearerAuth(credentials.accessToken); header("anthropic-beta", "oauth-2025-04-20")
        timeout { requestTimeoutMillis = 15_000 }
    }.execute { response ->
        if (response.status.value != 200) throw TeamFailure("usage_unavailable", 503, "Provider usage is unavailable.")
        parseUsage(account.id, response.boundedJson(), System.currentTimeMillis())
    }
    override suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit) {
        val metadata = body["metadata"] as? JsonObject
        val userId = (metadata?.get("user_id") as? JsonPrimitive)?.contentOrNull
        val identity = userId?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val outgoing = if (identity?.containsKey("account_uuid") == true) JsonObject(body + ("metadata" to JsonObject(metadata + ("user_id" to JsonPrimitive(JsonObject(identity + ("account_uuid" to JsonPrimitive(account.identity.accountId))).toString()))))) else body
        http.preparePost("https://api.anthropic.com/v1/messages") {
            bearerAuth(credentials.accessToken)
            contentType(ContentType.Application.Json)
            header("anthropic-version", headers["anthropic-version"] ?: "2023-06-01")
            header("anthropic-beta", ((headers["anthropic-beta"]?.split(',') ?: emptyList()) + "oauth-2025-04-20").map { it.trim() }.distinct().joinToString(","))
            header(HttpHeaders.UserAgent, headers["user-agent"] ?: "TeamCode/0.1.0")
            timeout { requestTimeoutMillis = 10 * 60_000; socketTimeoutMillis = 90_000 }
            setBody(outgoing.toString())
        }.execute { receive(it) }
    }
    companion object {
        fun parseUsage(accountId: String, value: JsonObject, now: Long): List<AccountQuota> = buildList {
            fun addBucket(name: String, utilization: JsonElement?, reset: JsonElement?) {
                val percent = (utilization as? JsonPrimitive)?.doubleOrNull ?: return
                val resets = (reset as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
                if (percent.isFinite() && percent >= 0 && resets > now) add(AccountQuota(accountId, name, (percent / 100).coerceAtMost(1.0), now, resets))
            }
            for ((name, element) in value) {
                val bucket = element as? JsonObject ?: continue
                val window = when {
                    name == "five_hour" -> "five_hour"
                    name == "seven_day" -> "seven_day"
                    name.startsWith("seven_day_") -> "model:" + name.removePrefix("seven_day_")
                    else -> continue
                }
                addBucket(window, bucket["utilization"], bucket["resets_at"])
            }
            (value["limits"] as? JsonArray)?.forEach { element ->
                val limit = element as? JsonObject ?: return@forEach
                val family = (((limit["scope"] as? JsonObject)?.get("model") as? JsonObject)?.get("display_name") as? JsonPrimitive)?.contentOrNull
                if ((limit["group"] as? JsonPrimitive)?.contentOrNull == "weekly" && !family.isNullOrBlank()) addBucket("model:${family.lowercase()}", limit["percent"], limit["resets_at"])
            }
        }.distinctBy { it.window }
        fun headerQuotas(accountId: String, headers: Headers, now: Long): List<AccountQuota> = listOf("5h" to "five_hour", "7d" to "seven_day", "7d_oi" to "model:fable").mapNotNull { (key, name) ->
            val used = if (headers["anthropic-ratelimit-unified-$key-status"] == "rejected") 1.0
                else headers["anthropic-ratelimit-unified-$key-utilization"]?.toDoubleOrNull()
            val reset = headers["anthropic-ratelimit-unified-$key-reset"]?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
            if (used != null && used.isFinite() && used >= 0 && reset != null && reset > now) AccountQuota(accountId, name, used.coerceAtMost(1.0), now, reset) else null
        }
    }
}
internal suspend fun HttpResponse.boundedJson(): JsonObject {
    val bytes = bodyAsChannel().readRemaining(262_145).readByteArray()
    if (bytes.size > 262_144) throw TeamFailure("provider_response_invalid", 502, "Provider response exceeds the limit.")
    return try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject } catch (_: IllegalArgumentException) { throw TeamFailure("provider_response_invalid", 502, "Provider returned an invalid response.") }
}
