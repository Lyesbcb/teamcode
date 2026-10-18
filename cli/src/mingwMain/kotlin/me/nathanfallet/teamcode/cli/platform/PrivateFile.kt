package me.nathanfallet.teamcode.cli.platform

actual fun writePrivateFile(path: String, content: String) {
    error("Private Windows configuration requires an owner-only DACL adapter; enrollment is not available on this target yet.")
}
