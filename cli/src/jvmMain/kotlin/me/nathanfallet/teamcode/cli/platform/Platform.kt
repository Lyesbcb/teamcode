package me.nathanfallet.teamcode.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

actual fun environment(name: String): String? = System.getenv(name)
actual fun httpEngine(): HttpClientEngineFactory<*> = CIO
