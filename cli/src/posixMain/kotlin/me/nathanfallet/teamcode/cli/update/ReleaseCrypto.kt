@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.update

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.process.teamcode_release_verify
import okio.ByteString.Companion.toByteString

internal actual fun verifyReleaseRsaPss(key: ReleasePublicKey, payload: ByteArray, signature: ByteArray): Boolean {
    if (key.spki.size !in 1..2048 || key.pkcs1.size !in 1..2048 || signature.size != 384) return false
    val digest = payload.toByteString().sha256().toByteArray()
    return key.spki.usePinned { spki -> key.pkcs1.usePinned { pkcs1 -> digest.usePinned { hash -> signature.usePinned { signed ->
        teamcode_release_verify(spki.addressOf(0).reinterpret(), key.spki.size.convert(), pkcs1.addressOf(0).reinterpret(), key.pkcs1.size.convert(), hash.addressOf(0).reinterpret(), digest.size.convert(), signed.addressOf(0).reinterpret(), signature.size.convert()) == 1
    } } } }
}
