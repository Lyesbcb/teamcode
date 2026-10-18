package me.nathanfallet.teamcode.cli.platform


expect fun environment(name: String): String?
expect fun writePrivateFile(path: String, content: String)

expect fun secureRandomBytes(count: Int): ByteArray

expect suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>): Int
