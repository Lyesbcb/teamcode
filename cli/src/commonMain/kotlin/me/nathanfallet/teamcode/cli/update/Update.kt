package me.nathanfallet.teamcode.cli.update

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.VERSION
import me.nathanfallet.teamcode.client.ReleaseClient
import me.nathanfallet.teamcode.client.ReleaseDownloadException

internal class Update : SuspendingCliktCommand(name = "update") {
    private val check by option("--check", help = "Check signed published metadata without installing or restarting anything.").flag()
    private val version by option("--version", help = "Check a specific version, including a prerelease; otherwise check the latest stable release.")
    override fun help(context: Context) = "Check signed releases. Installation and rollback are not available yet. No team login is required."

    override suspend fun run() {
        if (!check) throw UsageError("Use --check. Native installation and rollback are not available yet.")
        val result = try {
            ReleaseClient { configure -> HttpClient { configure(this) } }.use { CheckRelease(it, currentVersion = VERSION)(version) }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw CliOperationException("release_check_timeout")
        } catch (error: CancellationException) { throw error }
        catch (error: ReleaseDownloadException) { throw CliOperationException(error.code) }
        if (result == null) {
            echo("No stable published release is available. Current version: $VERSION.")
            return
        }
        echo("Current version: ${result.currentVersion}")
        echo("Verified release: ${result.release.manifest.version} (${result.release.manifest.sourceCommit})")
        echo("Release key: ${result.release.keyId}")
        echo("Update available: ${result.updateAvailable}")
        echo("Check only: no files were installed and no service was restarted.")
    }
}
