package me.nathanfallet.teamcode.domain.capacity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Development reference implementation. Production admission requires a durable transactional adapter. */
class AllowanceLedger {
    private data class Member(var spent: Long = 0, var reserved: Long = 0, val pending: MutableMap<String, Long> = mutableMapOf(), val settled: MutableSet<String> = mutableSetOf())
    private val members = mutableMapOf<String, Member>()
    private val mutex = Mutex()

    suspend fun reserve(memberId: String, requestId: String, amount: Long, limit: Long): Boolean = mutex.withLock {
        require(amount > 0 && limit >= 0)
        val member = members.getOrPut(memberId) { Member() }
        if (requestId in member.settled) return@withLock false
        member.pending[requestId]?.let { return@withLock it == amount }
        if (member.spent > limit || member.reserved > limit - member.spent || amount > limit - member.spent - member.reserved) {
            return@withLock false
        }
        member.pending[requestId] = amount
        member.reserved += amount
        true
    }

    /** Missing usage preserves the reservation. Repeat settlement never charges twice. */
    suspend fun settle(memberId: String, requestId: String, actual: Long?): Boolean = mutex.withLock {
        require(actual == null || actual >= 0)
        val member = members[memberId] ?: return@withLock false
        val reserved = member.pending[requestId] ?: return@withLock false
        if (actual == null) return@withLock false
        require(actual <= Long.MAX_VALUE - member.spent)
        member.pending.remove(requestId)
        member.settled.add(requestId)
        member.reserved -= reserved
        member.spent += actual
        true
    }

    suspend fun usage(memberId: String): Pair<Long, Long> = mutex.withLock {
        members[memberId]?.let { it.spent to it.reserved } ?: (0L to 0L)
    }
}
