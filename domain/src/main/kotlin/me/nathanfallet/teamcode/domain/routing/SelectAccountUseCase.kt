package me.nathanfallet.teamcode.domain.routing

import me.nathanfallet.teamcode.domain.accounts.AccountRecord

/** Candidates have already passed team/pool, quota, cooldown and concurrency admission. */
data class AccountPlacement(val account: AccountRecord, val activeRequests: Int, val activeConversations: Int, val utilization: Double)

class SelectAccountUseCase {
    fun select(eligible: List<AccountPlacement>, pinnedAccountIds: List<String>): AccountRecord? {
        val priority = eligible.maxOfOrNull { it.account.priority } ?: return null
        val preferred = eligible.filter { it.account.priority == priority }
        pinnedAccountIds.forEach { id -> preferred.firstOrNull { it.account.id == id }?.let { return it.account } }
        return preferred.minWithOrNull(compareBy<AccountPlacement>(
            { it.activeRequests }, { it.activeConversations }, { it.utilization }, { it.account.id },
        ))?.account
    }
}
