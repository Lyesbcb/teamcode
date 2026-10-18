package me.nathanfallet.teamcode.cli.platform

actual fun servicePlatform(): ServicePlatform = error("Windows service installation is not released yet.")
actual fun installServiceSignalHandlers(): Unit = error("Windows service signals are not released yet.")
actual fun serviceStopRequested(): Boolean = error("Windows service signals are not released yet.")
