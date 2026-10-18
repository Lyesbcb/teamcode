package me.nathanfallet.teamcode.cli.platform

actual suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, quiet: Boolean): Int =
    error("Agent launching on Windows requires the verified Windows process and private configuration adapters.")
