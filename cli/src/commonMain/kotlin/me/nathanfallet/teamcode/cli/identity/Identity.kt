package me.nathanfallet.teamcode.cli.identity

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import kotlin.time.Instant
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk

class WhoAmI : SuspendingCliktCommand(name = "whoami") {
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            val member = client.me()
            echo("${member.username} (${member.role.lowercase()})${if (member.passwordChangeRequired) " — password change required" else ""}")
        }
    }
}
class Auth : SuspendingNoOpCliktCommand(name = "auth") {
    override fun help(context: Context) = "Manage authentication. Agent run sessions are separate."
    init { subcommands(Sessions()) }
}
private class Sessions : SuspendingNoOpCliktCommand(name = "sessions") {
    init { subcommands(SessionList(), SessionRevoke()) }
}
private class SessionList : SuspendingCliktCommand(name = "list") {
    private val member by option("--member", help = "Member ID; other members require administrator access.")
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            for (session in client.authSessions(member)) echo("${session.id}\t${session.name}\t${if (session.current) "current" else "other"}\texpires ${Instant.fromEpochMilliseconds(session.expiresAt)}")
        }
    }
}
private class SessionRevoke : SuspendingCliktCommand(name = "revoke") {
    private val id by argument(help = "Session ID to revoke.")
    override suspend fun run() { sdk(loadConfig()).use { it.revokeAuthSession(id) }; echo("Session revoked.") }
}
class Devices : SuspendingNoOpCliktCommand(name = "devices") {
    init { subcommands(DeviceList(), DeviceRename(), DeviceRevoke()) }
}
private class DeviceList : SuspendingCliktCommand(name = "list") {
    private val member by option("--member", help = "Member ID; other members require administrator access.")
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            for (device in client.devices(member)) echo("${device.id}\t${device.name}\t${device.activeSessions} active sessions${if (device.current) "\tcurrent" else ""}")
        }
    }
}
private class DeviceRename : SuspendingCliktCommand(name = "rename") {
    private val id by argument(help = "Device ID to rename.")
    private val name by option("--name").required()
    override suspend fun run() { sdk(loadConfig()).use { it.renameDevice(id, name) }; echo("Device renamed.") }
}
private class DeviceRevoke : SuspendingCliktCommand(name = "revoke") {
    private val id by argument(help = "Device ID; every session on that device is revoked and the registration is removed.")
    override suspend fun run() { sdk(loadConfig()).use { it.revokeDevice(id) }; echo("Device removed and its sessions revoked. A password login is required to reconnect.") }
}
