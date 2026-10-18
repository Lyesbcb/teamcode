package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.EnrollAccountRequest
import kotlin.time.Clock

/** Provider login runs on the user's computer; only the resulting credentials are enrolled on TeamCode. */
class ClaudeEnrollmentClient(createHttpClient: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient) : AutoCloseable {
    private val http = createHttpClient {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
    }
    suspend fun exchange(code: String, state: String, verifier: String, operationId: String): EnrollAccountRequest = http.preparePost(TOKEN_URL) {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("code", code); put("state", state); put("code_verifier", verifier)
            put("grant_type", "authorization_code"); put("client_id", CLIENT_ID); put("redirect_uri", REDIRECT_URI)
        }.toString())
    }.execute { response ->
        if (response.status.value != 200) throw TeamCodeException(response.status.value, "Claude sign-in failed. Start a new sign-in attempt.")
        val bytes = response.bodyAsChannel().readRemaining(65_537).readByteArray()
        require(bytes.size <= 65_536) { "Invalid token response." }
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val access = value["access_token"]?.jsonPrimitive?.contentOrNull
        val refresh = value["refresh_token"]?.jsonPrimitive?.contentOrNull
        val seconds = value["expires_in"]?.jsonPrimitive?.longOrNull
        require(!access.isNullOrBlank() && !refresh.isNullOrBlank() && seconds != null && seconds in 1..31_536_000) { "Invalid token response." }
        EnrollAccountRequest(access, refresh, Clock.System.now().toEpochMilliseconds() + seconds * 1000, operationId)
    }
    override fun close() = http.close()
    companion object {
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"
        const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    }
}
