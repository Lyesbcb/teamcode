package me.nathanfallet.teamcode.infrastructure.gateway

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.AdmissionStore
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport

/** Sequential bounded sampling; stale data stays visible as stale instead of being replaced by zero. */
class QuotaMonitor(private val accounts: AccountStore, private val credentials: CredentialSource, private val admission: AdmissionStore, private val transport: ClaudeTransport) {
    private var offset = 0
    suspend fun sample(): Int {
        val candidates = accounts.list("default").filter { !it.disabled && it.state.canServe }
        if (candidates.isEmpty()) return 0
        var failures = 0
        repeat(minOf(candidates.size, 8)) {
            val account = candidates[offset % candidates.size]
            offset = (offset + 1) % candidates.size
            try { admission.observe(account.id, transport.usage(account, credentials.fresh(account))) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failures++ }
        }
        return failures
    }
}
