package me.nathanfallet.teamcode.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory

expect fun environment(name: String): String?
expect fun httpEngine(): HttpClientEngineFactory<*>
expect fun writePrivateFile(path: String, content: String)
