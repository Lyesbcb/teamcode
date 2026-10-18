package me.nathanfallet.teamcode

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import me.nathanfallet.teamcode.domain.gateway.GatewayService
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import me.nathanfallet.teamcode.presentation.configureGateway
import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun main() {
    require(System.getenv("TEAMCODE_MODE") == "fixture") {
        "Only explicit TEAMCODE_MODE=fixture is implemented. Real provider support is not available yet."
    }
    val token = requireNotNull(System.getenv("TEAMCODE_ACCESS_TOKEN")) { "Set TEAMCODE_ACCESS_TOKEN (at least 32 characters)." }
    val host = System.getenv("TEAMCODE_HOST") ?: "127.0.0.1"
    val port = (System.getenv("TEAMCODE_PORT") ?: "8080").toInt()
    val koin = koinApplication { modules(module { single<GatewayService> { FixtureGatewayService() } }) }
    try {
        embeddedServer(Netty, host = host, port = port) { configureGateway(koin.koin.get(), token) }.start(wait = true)
    } finally { koin.close() }
}
