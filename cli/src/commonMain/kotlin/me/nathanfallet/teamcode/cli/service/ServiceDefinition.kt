package me.nathanfallet.teamcode.cli.service

import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.platform.*
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath

@Serializable
internal class ServiceSettings(
    val id: String,
    val port: Int,
    val localToken: String,
    val executable: String,
    val config: String,
    val registration: String,
    val os: String,
    val userId: String,
) {
    init {
        require(Regex("[a-f0-9]{32}").matches(id) && port in 1..65535)
        require(Regex("[a-f0-9]{64}").matches(localToken))
        listOf(executable, config, registration).forEach { path ->
            require(path.toPath().isAbsolute && path.none { it.code < 32 || it.code == 127 })
        }
        require(os in setOf("macos", "linux", "windows"))
        require(if (os == "windows") Regex("S-1-[0-9-]{1,180}").matches(userId) else userId.isNotEmpty() && userId.all { it.isDigit() })
    }
    val label: String get() = "me.nathanfallet.teamcode.$id"
    override fun toString() = "ServiceSettings(id=$id, port=$port, localToken=[REDACTED])"
}

internal fun servicePath(config: String) = config.toPath().parent!!.resolve("service.json").toString()
internal fun serviceLogPath(config: String) = config.toPath().parent!!.resolve("service.log").toString()
internal fun serviceStopPath(config: String) = config.toPath().parent!!.resolve("service.stop").toString()
internal fun randomHex(bytes: Int) = secureRandomBytes(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun loadService(config: String): ServiceSettings? {
    val path = servicePath(config).toPath()
    val meta = FileSystem.SYSTEM.metadataOrNull(path) ?: return null
    require(meta.isRegularFile && meta.symlinkTarget == null && (meta.size ?: 0) <= 65_536)
    return Serialization.json.decodeFromString<ServiceSettings>(FileSystem.SYSTEM.read(path) { readUtf8() }).also {
        require(it.config == config) { "The service belongs to a different configuration." }
    }
}

internal fun serviceDefinition(settings: ServiceSettings): String {
    fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun unit(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "$$") + "\""
    val arguments = listOf(settings.executable, "service", "serve", "--config", settings.config)
    // systemd rejects quotes/backslashes in the executable token even after unquoting.
    // env execs the absolute path as an argument, preserving PID and literal $ expansion.
    // Keep existing ordinary registrations byte-compatible with ownsRegistration().
    val linuxArguments = if (settings.os == "linux" && settings.executable.any { it in setOf('\'', '"', '\\', '$') })
        listOf("/usr/bin/env", "--") + arguments else arguments
    return if (settings.os == "windows") """
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
        <RegistrationInfo><Description>TeamCode local authenticated proxy</Description></RegistrationInfo>
        <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>${xml(settings.userId)}</UserId></LogonTrigger></Triggers>
        <Principals><Principal id="TeamCodeUser"><UserId>${xml(settings.userId)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
        <Settings>
        <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
        <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
        <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
        <AllowHardTerminate>false</AllowHardTerminate>
        <StartWhenAvailable>true</StartWhenAvailable>
        <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
        <AllowStartOnDemand>true</AllowStartOnDemand><Enabled>true</Enabled><Hidden>false</Hidden>
        <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
        <RestartOnFailure><Interval>PT1M</Interval><Count>255</Count></RestartOnFailure>
        </Settings>
        <Actions Context="TeamCodeUser"><Exec><Command>${xml(settings.executable)}</Command>
        <Arguments>${xml(arguments.drop(1).joinToString(" ", transform = ::quoteWindowsArgument))}</Arguments></Exec></Actions>
        </Task>
    """.trimIndent() + "\n" else if (settings.os == "macos") """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>
        <key>Label</key><string>${settings.label}</string>
        <key>ProgramArguments</key><array>${arguments.joinToString("") { "<string>${xml(it)}</string>" }}</array>
        <key>RunAtLoad</key><true/>
        <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
        <key>ThrottleInterval</key><integer>30</integer>
        <key>ExitTimeOut</key><integer>660</integer>
        <key>ProcessType</key><string>Standard</string>
        <key>StandardOutPath</key><string>/dev/null</string>
        <key>StandardErrorPath</key><string>/dev/null</string>
        </dict></plist>
    """.trimIndent() + "\n" else """
        [Unit]
        Description=TeamCode local authenticated proxy
        StartLimitIntervalSec=0

        [Service]
        Type=exec
        ExecStart=${linuxArguments.joinToString(" ", transform = ::unit)}
        Restart=on-failure
        RestartSec=30
        TimeoutStopSec=660
        UMask=0077
        NoNewPrivileges=yes
        StandardOutput=null
        StandardError=null

        [Install]
        WantedBy=default.target
    """.trimIndent() + "\n"
}

/** Only a byte-for-byte matching registration may be managed or removed. */
internal fun ownsRegistration(settings: ServiceSettings): Boolean {
    val path = settings.registration.toPath()
    val meta = FileSystem.SYSTEM.metadataOrNull(path) ?: return false
    require(meta.isRegularFile && meta.symlinkTarget == null && (meta.size ?: 0) <= 65_536)
    require(FileSystem.SYSTEM.read(path) { readByteArray() }.contentEquals(registrationBytes(settings))) {
        "The service registration was modified externally; refusing to replace or remove it."
    }
    return true
}

internal fun writeRegistration(settings: ServiceSettings) {
    val path = settings.registration.toPath()
    val fs = FileSystem.SYSTEM
    require(fs.metadataOrNull(path.parent!!)?.symlinkTarget == null)
    fs.createDirectories(path.parent!!)
    if (ownsRegistration(settings)) return
    // Do not chmod a shared LaunchAgents/systemd directory. The manifest contains no credentials.
    fs.write(path, mustCreate = true) { write(registrationBytes(settings)) }
}

/** Task Scheduler consumes a Unicode XML document; match its declaration and BOM. */
internal fun registrationBytes(settings: ServiceSettings): ByteArray {
    val xml = serviceDefinition(settings)
    if (settings.os != "windows") return xml.encodeToByteArray()
    return Buffer().apply {
        writeShortLe(0xfeff)
        xml.forEach { writeShortLe(it.code) }
    }.readByteArray()
}
