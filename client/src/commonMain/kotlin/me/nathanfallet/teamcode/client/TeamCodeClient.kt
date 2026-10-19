package me.nathanfallet.teamcode.client

import kotlinx.coroutines.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.get
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.*

/** The factory must create independently owned HTTP clients; this SDK owns their lifetime. */
class TeamCodeClient(
    serverUrl: String,
    private val token: suspend () -> String?,
    private val createHttpClient: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
) : AutoCloseable {
    private val origin = Url(serverUrl.also { require(it.isNotBlank()) { "A TeamCode server URL is required." } }).also {
        require(it.protocol == URLProtocol.HTTPS ||
            (it.protocol == URLProtocol.HTTP && it.host in setOf("localhost", "127.0.0.1", "::1"))) {
            "TeamCode requires HTTPS except on loopback."
        }
        require(it.user.isNullOrEmpty() && it.password.isNullOrEmpty() && it.encodedPath in setOf("", "/") &&
            it.parameters.isEmpty() && it.fragment.isEmpty()) { "Use a server origin without credentials, path, query or fragment." }
    }.toString().trimEnd('/')
    private val configureHttpClient: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        followRedirects = false // A redirect must not carry a team credential to another origin.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 300_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        install(Resources)
        install(ContentNegotiation) { json(Serialization.json) }
    }

    private val http = createHttpClient(configureHttpClient)

    private val inferenceClosed = CompletableDeferred<Unit>()

    suspend fun capabilities(): Capabilities {
        val response = http.get(CapabilitiesResource()) { url.takeFrom("$origin/api/v1/capabilities"); authenticate(); timeout { requestTimeoutMillis = 10_000 } }
        checkResponse(response)
        return response.body()
    }

    suspend fun status(): GatewayStatus {
        val response = http.get(StatusResource()) { url.takeFrom("$origin/api/v1/status"); authenticate(); timeout { requestTimeoutMillis = 10_000 } }
        checkResponse(response)
        return response.body()
    }

    suspend fun login(username: String, password: String, device: String = "TeamCode CLI"): LoginResponse =
        request("/api/v1/auth/login", HttpMethod.Post, LoginRequest(username, password, device))
    suspend fun me(): MemberView = request("/api/v1/auth/me", HttpMethod.Get)
    suspend fun changePassword(current: String, replacement: String): SuccessResponse =
        request("/api/v1/auth/password", HttpMethod.Post, PasswordRequest(current, replacement))
    suspend fun logout(): SuccessResponse = request("/api/v1/auth/logout", HttpMethod.Post)
    suspend fun directive(sessionId: String? = null): DirectiveResponse = if (sessionId == null)
        request("/api/v1/directives/current", HttpMethod.Get) else request("/api/v1/directives/current", HttpMethod.Post, DirectiveScope(sessionId))
    suspend fun claimDirective(sessionId: String, stage: String): DirectiveClaimResponse = request("/api/v1/directives/claim", HttpMethod.Post, DirectiveClaimRequest(sessionId, stage))
    suspend fun acknowledgeDirective(id: String, stage: String, sessionId: String? = null, claimId: String? = null): SuccessResponse = request("/api/v1/directives/receipt", HttpMethod.Post, DirectiveReceipt(id, stage, sessionId, claimId))
    suspend fun capacity(): CapacityView = request("/api/v1/capacity", HttpMethod.Get)
    suspend fun accounts(): List<AccountView> = request("/api/v1/accounts", HttpMethod.Get)
    suspend fun enrollAccount(input: EnrollAccountRequest): EnrollAccountResponse = request("/api/v1/accounts", HttpMethod.Post, input)
    suspend fun handoverAccount(id: String, accessOnly: Boolean = false): AccountView {
        require(Regex("[a-zA-Z0-9-]{1,36}").matches(id))
        return request("/api/v1/accounts/$id/${if (accessOnly) "access-only" else "handover"}", HttpMethod.Post)
    }
    suspend fun routes(): List<RouteRuleView> = request("/api/v1/routes", HttpMethod.Get)
    suspend fun saveRoute(rule: RouteRuleView): RouteRuleView {
        require(Regex("[a-zA-Z0-9_-]{1,36}").matches(rule.id))
        return request("/api/v1/routes/${rule.id}", HttpMethod.Put, rule)
    }
    suspend fun deleteRoute(id: String, version: Long): SuccessResponse {
        require(Regex("[a-zA-Z0-9_-]{1,36}").matches(id) && version > 0)
        return request("/api/v1/routes/$id", HttpMethod.Delete, RouteDeleteRequest(version))
    }
    suspend fun members(): List<MemberView> = request("/api/v1/members", HttpMethod.Get)

    private suspend inline fun <reified R> request(path: String, method: HttpMethod): R {
        val response = http.request("$origin$path") { this.method = method; authenticate(); timeout { requestTimeoutMillis = 10_000 } }
        checkResponse(response)
        return response.body()
    }
    private suspend inline fun <reified R, reified B> request(path: String, method: HttpMethod, body: B): R {
        val response = http.request("$origin$path") { this.method = method; contentType(ContentType.Application.Json); setBody(body); authenticate(); timeout { requestTimeoutMillis = 30_000 } }
        checkResponse(response)
        return response.body()
    }

    /** The body must be consumed inside [receive]; no full response is buffered by this SDK. */
    suspend fun forwardMessages(
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        receive: suspend (HttpResponse) -> Unit,
    ) = forwardInference("/v1/messages", body, headers, receive)

    suspend fun forwardInference(path: String, body: ByteArray, headers: Map<String, String> = emptyMap(), receive: suspend (HttpResponse) -> Unit) {
        require(path in setOf("/v1/messages", "/v1/responses")) { "Unsupported inference path." }
        check(!inferenceClosed.isCompleted) { "The TeamCode client is closed." }
        // Curl/WinHTTP can retain a pre-header request after coroutine cancellation. An
        // independently owned engine lets us close that transport without cancelling peers.
        // Management keeps its shared connection pool; inference engines live for one call.
        createHttpClient(configureHttpClient).use { inference ->
            lateinit var requestJob: Job
            val statement = inference.preparePost("$origin$path") {
                requestJob = executionContext
                contentType(ContentType.Application.Json)
                for ((name, value) in headers) {
                    require(name.lowercase() in FORWARDED_HEADERS) { "Unsupported inference header." }
                    header(name, value)
                }
                authenticate()
                setBody(body)
            }
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { inferenceClosed.await() }
                    finally { requestJob.cancel(); inference.close() }
                }
                try { statement.execute { response -> receive(response) } }
                finally { cancellation.cancel() }
            }
        }
    }

    private suspend fun HttpRequestBuilder.authenticate() {
        token()?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
    }

    private suspend fun checkResponse(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            // Do not expose raw response bodies: a proxy/provider may echo secrets.
            throw TeamCodeException(response.status.value, "TeamCode returned HTTP ${response.status.value}.")
        }
    }

    override fun close() { inferenceClosed.complete(Unit); http.close() }

    companion object {
        val FORWARDED_HEADERS: Set<String> = me.nathanfallet.teamcode.api.InferenceHeaders.names
    }
}

class TeamCodeException(val statusCode: Int, message: String) : Exception(message)
