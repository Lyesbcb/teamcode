package me.nathanfallet.teamcode.infrastructure.routing

import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.RouteTarget

/** Shared by admission hints and forecast context validation. */
internal fun routeFingerprint(protocol: WireProtocol, model: String, targets: List<RouteTarget>): String {
    val routes = targets.joinToString("\n") { "${it.provider}:${it.model ?: model}" }
    return MessageDigest.getInstance("SHA-256").digest("${protocol.name}\n$routes".toByteArray()).joinToString("") { "%02x".format(it) }
}
