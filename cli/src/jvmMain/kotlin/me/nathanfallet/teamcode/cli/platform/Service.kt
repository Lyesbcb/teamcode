package me.nathanfallet.teamcode.cli.platform

actual fun servicePlatform(): ServicePlatform = error("Service installation requires the native TeamCode executable.")
actual fun installServiceSignalHandlers() = Unit
actual fun serviceStopRequested(): Boolean = false
