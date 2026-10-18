package me.nathanfallet.teamcode.cli.platform

import kotlinx.coroutines.*

actual suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>): Int {
    val builder = ProcessBuilder(listOf(command) + arguments).inheritIO()
    overrides.forEach { (name, value) -> if (value == null) builder.environment().remove(name) else builder.environment()[name] = value }
    val child = builder.start()
    try {
        while (child.isAlive) delay(50)
        return child.exitValue()
    } finally {
        if (child.isAlive) {
            child.destroy()
            withContext(NonCancellable + Dispatchers.IO) {
                if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    child.destroyForcibly(); child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
    }
}
