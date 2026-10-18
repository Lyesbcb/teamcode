package me.nathanfallet.teamcode.cli.service

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.config.*
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path.Companion.toPath

class Service : SuspendingNoOpCliktCommand(name = "service") {
    override fun help(context: Context) = "Manage the local proxy as a user service, starting automatically at login."
    init { subcommands(Install(), Print(), Manage("start"), Manage("stop"), Manage("restart"), Manage("uninstall"), Status(), Logs(), Serve()) }
}

private fun settings(config: String, port: Int, executable: String?): ServiceSettings {
    val platform = servicePlatform()
    require(platform.userId != "0") { "Install a user service without sudo. System service installation is not available yet." }
    val home = requireNotNull(environment("HOME")) { "Cannot locate the user home directory." }
    val id = randomHex(16)
    val label = "me.nathanfallet.teamcode.$id"
    val registration = if (platform.os == "macos") "$home/Library/LaunchAgents/$label.plist"
        else "${config.toPath().parent!!.parent}/systemd/user/$label.service"
    return ServiceSettings(id, port, randomHex(32), executable ?: platform.executable, config, registration, platform.os, platform.userId)
}

private class Print : SuspendingCliktCommand(name = "print") {
    private val port by option("--port").int().default(8789)
    private val binary by option("--binary", help = "Stable absolute path to the native TeamCode executable.")
    override suspend fun run() { echo(serviceDefinition(loadService(configPath()) ?: settings(configPath(), port, binary))) }
}

private class Install : SuspendingCliktCommand(name = "install") {
    private val port by option("--port").int()
    private val binary by option("--binary", help = "Stable absolute path; defaults to the running executable.")
    override suspend fun run() {
        val config = configPath()
        requireNotNull(ConfigStore(config).load()) { "Run teamcode login before installing a service." }
        val existing = loadService(config)
        val selected = existing ?: settings(config, port ?: 8789, binary)
        require(port == null || port == selected.port) { "Uninstall before changing the service port." }
        require(binary == null || binary == selected.executable) { "Uninstall before changing the service executable." }
        require(FileSystem.SYSTEM.metadataOrNull(selected.executable.toPath())?.isRegularFile == true) { "The executable must exist at a stable absolute path." }
        if (existing == null) writePrivateFile(servicePath(config), Serialization.json.encodeToString(ServiceSettings.serializer(), selected))
        writeRegistration(selected)
        if (selected.os == "linux") {
            manager(selected, "daemon-reload")
            manager(selected, "enable")
        }
        start(selected)
        echo("Installed user service ${selected.label}; it starts automatically at login. Local port: ${selected.port}.")
        echo("Use teamcode service status to check local and upstream availability separately.")
    }
}

private suspend fun manager(settings: ServiceSettings, action: String, checkResult: Boolean = true): Int {
    val command: String
    val args: List<String>
    if (settings.os == "macos") {
        command = "/bin/launchctl"
        val domain = "gui/${settings.userId}"
        val target = "$domain/${settings.label}"
        args = when (action) {
            "status" -> listOf("print", target)
            "start" -> listOf("kickstart", target)
            "load" -> listOf("bootstrap", domain, settings.registration)
            "stop" -> listOf("bootout", target)
            else -> error("Unsupported launchd operation.")
        }
    } else {
        command = "/usr/bin/systemctl"
        args = listOf("--user") + if (action == "daemon-reload") listOf(action)
            else listOf(action, if (action == "enable") settings.registration else "${settings.label}.service")
    }
    val result = withTimeout(if (action in setOf("stop", "restart")) 670_000L else 15_000L) {
        runInteractiveProcess(command, args, emptyMap(), quiet = action == "status")
    }
    if (checkResult) check(result == 0) { "The OS service manager rejected the operation." }
    return result
}

private suspend fun start(settings: ServiceSettings) {
    require(ownsRegistration(settings)) { "The service registration is missing. Run service install." }
    if (settings.os == "macos" && manager(settings, "status", false) != 0) manager(settings, "load")
    else manager(settings, "start")
}

private suspend fun stop(settings: ServiceSettings) {
    require(ownsRegistration(settings)) { "The service registration is missing; refusing to stop an unknown service." }
    if (settings.os != "macos" || manager(settings, "status", false) == 0) manager(settings, "stop")
    // bootout can return while the old process is still draining. Do not race its replacement.
    if (settings.os == "macos") withTimeout(660_000) {
        while (manager(settings, "status", false) == 0) delay(250)
    }
}

private class Manage(private val operation: String) : SuspendingCliktCommand(name = operation) {
    override suspend fun run() {
        val selected = loadService(configPath())
        if (selected == null) {
            require(operation == "uninstall" || operation == "stop") { "Run teamcode service install first." }
            echo("No TeamCode service is installed for this configuration.")
            return
        }
        when (operation) {
            "start" -> start(selected)
            "stop" -> stop(selected)
            "restart" -> { echo("Draining the local proxy before restarting."); stop(selected); start(selected) }
            "uninstall" -> {
                require(ownsRegistration(selected)) { "The registration is missing. Repair it with service install before uninstalling." }
                stop(selected)
                if (selected.os == "linux") manager(selected, "disable")
                FileSystem.SYSTEM.delete(selected.registration.toPath())
                if (selected.os == "linux") manager(selected, "daemon-reload")
                FileSystem.SYSTEM.delete(servicePath(selected.config).toPath())
                echo("Removed the TeamCode service. Saved login and logs are preserved.")
            }
        }
    }
}

private class Status : SuspendingCliktCommand(name = "status") {
    override suspend fun run() {
        val selected = loadService(configPath())
        echo("Installed: ${selected != null && ownsRegistration(selected)}")
        val config = ConfigStore().load()
        echo("Running: ${selected != null && config != null && serviceResponds(selected, config.serverUrl)}")
        if (config == null) { echo("Authenticated: no saved login"); return }
        try {
            sdk(config).use { it.me() }
            echo("Connected: true | Authenticated: true")
        } catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { echo("Connected: true | Authenticated: ${if (error.statusCode == 401) "false" else "unverified (HTTP ${error.statusCode})"}") }
        catch (_: Exception) { echo("Connected: false | Authenticated: unverified (server unreachable)") }
    }
}

private class Logs : SuspendingCliktCommand(name = "logs") {
    override suspend fun run() {
        val path = serviceLogPath(configPath()).toPath()
        val meta = FileSystem.SYSTEM.metadataOrNull(path)
        if (meta == null) { echo("No service logs yet."); return }
        require(meta.symlinkTarget == null && meta.isRegularFile && (meta.size ?: 0) <= 65_536)
        echo(FileSystem.SYSTEM.read(path) { readUtf8() })
    }
}

private class Serve : SuspendingCliktCommand(name = "serve") {
    private val config by option("--config", help = "Absolute saved configuration path, supplied by the service manager.").required()
    override suspend fun run() {
        require(config.toPath().isAbsolute)
        serve(requireNotNull(loadService(config)) { "Service settings are missing." })
    }
}
