package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class TeamRoutesTest {
    @Test fun registrationIsAbsentAndTemporaryPasswordCannotReachInference() = testApplication {
        val team = FakeTeam()
        application { configureGateway(NoGateway(), team = team, publicOrigin = "http://localhost") }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/register").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/status") { bearerAuth("temporary") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") { bearerAuth("temporary") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/members") { bearerAuth("member") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/members") { bearerAuth("admin") }.status)
    }
    @Test fun browserActionsRequireOriginAndCsrfAndApiIgnoresCookies() = testApplication {
        val team = FakeTeam()
        application { configureGateway(NoGateway(), team = team, publicOrigin = "http://localhost") }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/members") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.status)
        val page = client.get("/dashboard/members") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("name='csrf'"))
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        suspend fun submit(origin: String, body: String) = client.post("/dashboard/members") {
            header(HttpHeaders.Cookie, "teamcode_session=admin")
            header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Forbidden, submit("https://attacker.test", "csrf=session&username=alice&role=MEMBER").status)
        assertEquals(HttpStatusCode.Forbidden, submit("http://localhost", "csrf=wrong&username=alice&role=MEMBER").status)
        assertEquals(0, team.created)
        val created = submit("http://localhost", "csrf=session&username=alice&role=MEMBER")
        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals(1, team.created)
        assertTrue(created.bodyAsText().contains("synthetic-temporary-password"))
        assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/login") {
            header(HttpHeaders.Origin, "https://attacker.test")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("username=admin&password=password")
        }.status)
    }
}
private class NoGateway : GatewayService {
    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { error("No inference expected") }
    override fun status() = GatewaySnapshot("test", 0, 0)
}
private class FakeTeam : TeamService {
    var created = 0
    private val admin = Principal(Member("admin-id", "admin", Role.ADMIN, false, false), "session", "default")
    override suspend fun authenticate(token: String) = when (token) {
        "admin" -> admin
        "temporary" -> admin.copy(member = admin.member.copy(passwordChangeRequired = true))
        "member" -> admin.copy(member = admin.member.copy(role = Role.MEMBER))
        else -> null
    }
    override suspend fun login(username: String, password: String, device: String) = LoginSession("admin", admin, Long.MAX_VALUE)
    override suspend fun logout(principal: Principal) = Unit
    override suspend fun changePassword(principal: Principal, current: String, replacement: String) = Unit
    override suspend fun members(principal: Principal): List<Member> { principal.requireAdmin(); return listOf(admin.member) }
    override suspend fun createMember(principal: Principal, username: String, role: Role): NewMember { principal.requireAdmin(); created++; return NewMember(Member("new-id", username, role, false, true), "synthetic-temporary-password") }
    override suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean) = error("unused")
    override suspend fun resetPassword(principal: Principal, id: String) = error("unused")
}
