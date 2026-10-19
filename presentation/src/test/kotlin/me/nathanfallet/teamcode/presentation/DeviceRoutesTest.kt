package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class DeviceRoutesTest {
    private val deviceId = "00000000-0000-0000-0000-000000000001"
    private class Registry : DeviceService {
        var name = "Developer laptop"
        var reads = 0; var renamed = 0
        val revoked = mutableListOf<String>()
        private fun authorize(actor: Principal, member: String) { actor.requireReady(); if (actor.member.id != member) actor.requireAdmin() }
        override suspend fun devices(actor: Principal, memberId: String): List<Device> {
            authorize(actor, memberId); reads++
            return listOf(Device("00000000-0000-0000-0000-000000000001", memberId, name, 1, 2, 1, true))
        }
        override suspend fun sessions(actor: Principal, memberId: String): List<MemberSession> {
            authorize(actor, memberId); reads++
            return listOf(MemberSession("session", memberId, "Legacy browser", null, null, Long.MAX_VALUE, true))
        }
        override suspend fun rename(actor: Principal, deviceId: String, name: String): Device {
            actor.requireReady(); DeviceService.validateName(name); this.name = name; renamed++
            return Device(deviceId, actor.member.id, name, 1, 2, 1, true)
        }
        override suspend fun revokeDevice(actor: Principal, deviceId: String): String { revoked += deviceId; return actor.member.id }
        override suspend fun revokeSession(actor: Principal, sessionId: String): String { revoked += sessionId; return actor.member.id }
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("No inference expected")
    }

    @Test fun sdkUsesTypedMetadataAndApiRequiresBearerAndTargetPermission() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway, team = FakeTeam(), devices = registry) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/devices").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/sessions") { header(HttpHeaders.Cookie, "teamcode_session=member") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/devices") { bearerAuth("temporary") }.status)
        assertEquals(0, registry.reads)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/members/another/devices") { bearerAuth("member") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/members/another/devices") { bearerAuth("admin") }.status)
        val sdk = TeamCodeClient("http://localhost", { "member" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(deviceId, sdk.devices().single().id)
            assertTrue(sdk.devices().single().current)
            val session = sdk.authSessions().single()
            assertTrue(session.current); assertNull(session.createdAt); assertNull(session.deviceId)
            assertEquals("Personal laptop", sdk.renameDevice(deviceId, "Personal laptop").name)
            sdk.revokeAuthSession("session"); sdk.revokeDevice(deviceId)
            assertEquals(listOf("session", deviceId), registry.revoked)
        } finally { sdk.close() }
        val raw = client.get("/api/v1/devices") { bearerAuth("member") }.bodyAsText()
        for (privateField in listOf("installationId", "tokenHash", "accessToken", "password")) assertFalse(raw.contains(privateField))
    }

    @Test fun browserMutationNeedsOriginAndCsrfAndEscapesDeviceNames() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway, team = FakeTeam(), publicOrigin = "http://localhost", devices = registry) }
        val action = "/dashboard/devices/$deviceId/rename"
        assertEquals(HttpStatusCode.Forbidden, client.post(action) {
            header(HttpHeaders.Cookie, "teamcode_session=member"); header(HttpHeaders.Origin, "https://attacker.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&name=Attack")
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(action) {
            header(HttpHeaders.Cookie, "teamcode_session=member"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=wrong&name=Attack")
        }.status)
        assertEquals(0, registry.renamed)
        assertEquals(HttpStatusCode.Found, client.post(action) {
            header(HttpHeaders.Cookie, "teamcode_session=member"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&name=%3Cscript%3Ealert%281%29%3C%2Fscript%3E")
        }.status)
        val html = client.get("/dashboard/devices") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText()
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>"))
        assertTrue(html.contains("Unknown (older session)")); assertTrue(html.contains("Current session"))
        assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/members/another/devices") { header(HttpHeaders.Cookie, "teamcode_session=member") }.status)
        val revoked = client.post("/dashboard/devices/sessions/session/revoke") {
            header(HttpHeaders.Cookie, "teamcode_session=member"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session")
        }
        assertEquals(HttpStatusCode.Found, revoked.status)
        assertEquals("/dashboard/login", revoked.headers[HttpHeaders.Location])
        assertTrue(revoked.headers[HttpHeaders.SetCookie]!!.contains("Max-Age=0"))
    }
}
