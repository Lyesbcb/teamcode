package me.nathanfallet.teamcode.cli.update

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.cli.CliOperationException
import okio.Path.Companion.toPath

internal class VerifyRelease : SuspendingCliktCommand(name = "verify-release") {
    private val manifest by option("--manifest").required()
    private val signature by option("--signature").required()
    private val binary by option("--binary")
    private val target by option("--target")
    override fun help(context: Context) = "Verify a downloaded release against the public roots shipped in this executable. No login or server is used."
    override suspend fun run() {
        if ((binary == null) != (target == null)) throw CliOperationException("release_binary_target_required")
        val verified = VerifyReleaseManifest().verify(readReleaseFile(manifest.toPath(), ReleaseFormat.MAX_MANIFEST_BYTES), readReleaseFile(signature.toPath(), ReleaseFormat.MAX_SIGNATURE_BYTES))
        binary?.let { verified.verifyBinary(it.toPath(), requireNotNull(target)) }
        echo("Verified TeamCode ${verified.manifest.version} (${verified.manifest.sourceCommit}).")
        echo("Release key: ${verified.keyId}")
        if (binary != null) echo("Verified binary for $target.")
    }
}
