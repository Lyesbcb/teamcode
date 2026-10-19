package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.api.models.ModelPolicyView
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ModelPolicyRoutesTest {
    @Test fun experimentalTranslationRequiresAdminAndBrowserCsrfAndDefaultsOff() = testApplication {
        var stored = ModelPolicy()
        val policies = object : ModelPolicyStore {
            override suspend fun get(teamId: String) = stored
            override suspend fun save(actor: Principal, policy: ModelPolicy): ModelPolicy {
                actor.requireAdmin(); CheckModelPolicyUseCase().validateConfiguration(policy)
                stored = policy; return stored
            }
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
        }
        application { configureGateway(gateway, team = FakeTeam(), publicOrigin = "http://localhost", modelPolicies = policies) }
        suspend fun read() = Json.decodeFromString<ModelPolicyView>(client.get("/api/v1/policy/models") { bearerAuth("member") }.bodyAsText())
        val before = read()
        assertFalse(before.experimentalCrossProviderTranslation)
        val enabled = Json.encodeToString(before.copy(experimentalCrossProviderTranslation = true))
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/policy/models") {
            bearerAuth("member"); contentType(ContentType.Application.Json); setBody(enabled)
        }.status)
        assertFalse(stored.experimentalCrossProviderTranslation)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/policy/models") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(enabled)
        }.status)
        assertTrue(read().experimentalCrossProviderTranslation)
        val html = client.get("/dashboard/models") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(html.contains("experimentalTranslation")); assertTrue(html.contains("experimental cross-provider translation"))
        val fields = "allowed=claude-*&denied=*fable*&efforts=provider-default&thinking=32768"
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/models") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://attacker.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&$fields")
        }.status)
        assertTrue(stored.experimentalCrossProviderTranslation)
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/models") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=wrong&$fields")
        }.status)
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/models") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&$fields")
        }.status)
        assertFalse(read().experimentalCrossProviderTranslation)
        // Older JSON clients omit the flag: they must not accidentally activate the experiment.
        val legacy = """{"allowedModels":["claude-*"],"deniedModels":[],"allowedEfforts":["provider-default"],"maximumThinkingTokens":32768}"""
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/policy/models") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(legacy)
        }.status)
        assertFalse(read().experimentalCrossProviderTranslation)
    }
}
