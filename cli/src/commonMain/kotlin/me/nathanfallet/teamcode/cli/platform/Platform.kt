package me.nathanfallet.teamcode.cli.platform


expect fun environment(name: String): String?
expect fun platformArguments(arguments: Array<String>): Array<String>
expect fun writePrivateFile(path: String, content: String)

expect fun secureRandomBytes(count: Int): ByteArray

/** Read at most 64 KiB from redirected stdin, requiring EOF within one second. Never read a terminal. */
internal expect fun readHookInput(): ByteArray?

expect suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, quiet: Boolean = false): Int

data class ServicePlatform(val os: String, val executable: String, val userId: String)
expect fun servicePlatform(): ServicePlatform
expect fun installServiceSignalHandlers()
expect fun serviceStopRequested(): Boolean
