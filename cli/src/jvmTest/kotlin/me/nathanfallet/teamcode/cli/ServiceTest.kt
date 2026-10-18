package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.cli.service.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class ServiceTest {
    private val token = "b".repeat(64)
    private fun settings(os: String) = ServiceSettings("a".repeat(32), 8789, token,
        "/home/user/Team & Code/\"100%\$x\"", "/home/user/config dir/config.json", "/home/user/service", os, "501")

    @Test fun manifestsPreserveLiteralPathsWithoutExposingCredentials() {
        val mac = settings("macos")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(serviceDefinition(mac).toByteArray()))
        val strings = document.getElementsByTagName("array").item(0).childNodes
        val arguments = (0 until strings.length).map { strings.item(it).textContent }
        assertEquals(listOf(mac.executable, "service", "serve", "--config", mac.config), arguments)
        val linux = serviceDefinition(settings("linux"))
        assertTrue(linux.contains("100%%\$\$x"))
        assertTrue(linux.contains("WantedBy=default.target"))
        assertTrue(linux.contains("TimeoutStopSec=660"))
        assertFalse(linux.contains(token))
        assertFalse(serviceDefinition(mac).contains(token))
        assertFalse(mac.toString().contains(token))
    }

    @Test fun serviceIdentityRequiresLocalCapabilityAndRejectsBrowserAccess() = testApplication {
        val sdk = TeamCodeClient("https://team.example", { "server-secret" }) { config ->
            HttpClient(MockEngine { fail("Service identity should not call upstream") }) { config(this) }
        }
        application { localProxy(sdk, token, serviceId = "installation", serverUrl = "https://team.example") }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/_teamcode/service").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/_teamcode/service") { bearerAuth(token); header(HttpHeaders.Origin, "https://browser.example") }.status)
            val response = client.get("/_teamcode/service") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertTrue(response.bodyAsText().contains("installation"))
            assertFalse(response.bodyAsText().contains("server-secret"))
            assertFalse(response.bodyAsText().contains(token))
        } finally { sdk.close() }
    }
}
