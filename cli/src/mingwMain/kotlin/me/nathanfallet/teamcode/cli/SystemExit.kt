package me.nathanfallet.teamcode.cli

actual object SystemExit {
    actual fun fail(message: String): Nothing {
        println(message)
        kotlin.system.exitProcess(1)
    }
}
