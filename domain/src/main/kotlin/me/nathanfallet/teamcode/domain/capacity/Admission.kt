package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.team.Principal

data class MemberPolicy(val memberId: String, val dailyUnits: Long = 0, val maxConcurrent: Int = 2, val poolId: String? = null, val weight: Int = 1)
data class MemberCapacity(val policy: MemberPolicy, val chargedAndReservedUnits: Long, val activeRequests: Int, val resetsAtMillis: Long, val dailyAllowanceUnits: Long = policy.dailyUnits, val poolDailyUnits: Long = 1_000_000, val poolTotalWeight: Long = 1)
data class PoolBudget(val poolId: String, val dailyUnits: Long)
data class Reservation(val id: String, val account: AccountRecord, val units: Long)
data class AccountQuota(val accountId: String, val window: String, val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long)
fun AccountQuota.appliesTo(model: String): Boolean = !window.startsWith("model:") || model.lowercase().contains(window.removePrefix("model:").substringBefore(':').lowercase())
data class AdmissionStatistics(val active: Int, val completed: Long)
interface AdmissionStore {
    suspend fun statistics(): AdmissionStatistics
    suspend fun reserve(actor: Principal, units: Long, model: String, provider: String = "anthropic", excludedAccountIds: Set<String> = emptySet()): Reservation
    /** Missing usage conservatively retains the full reservation, including on disconnect/restart. */
    suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean = false)
    suspend fun capacity(actor: Principal, memberId: String = actor.member.id): MemberCapacity
    suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy
    suspend fun poolBudgets(actor: Principal): List<PoolBudget>
    suspend fun configurePool(actor: Principal, budget: PoolBudget): PoolBudget
    suspend fun quotas(actor: Principal): List<AccountQuota>
    suspend fun observe(accountId: String, observations: List<AccountQuota>)
    suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean = false)
}
