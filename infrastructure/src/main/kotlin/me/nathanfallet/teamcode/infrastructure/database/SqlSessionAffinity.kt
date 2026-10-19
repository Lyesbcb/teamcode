package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.infrastructure.database.tables.ConversationAffinities as Hints
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Called inside admission's team-locked transaction. Contains no process-local routing state. */
internal class SqlSessionAffinity {
    private fun key(actor: Principal, affinity: RequestAffinity) =
        (Hints.teamId eq actor.teamId) and (Hints.memberId eq actor.member.id) and
            (Hints.conversationKey eq affinity.conversationKey) and (Hints.requestedModel eq affinity.requestedModel) and (Hints.routeKey eq affinity.routeKey)
    fun target(c: JdbcTransaction, actor: Principal, affinity: RequestAffinity, now: Long): RouteTarget? =
        Hints.select(Hints.provider, Hints.resolvedModel).where { key(actor, affinity) and (Hints.expiresAt greater now) }
            .singleOrNull()?.let { RouteTarget(it[Hints.provider], it[Hints.resolvedModel]) }

    fun accounts(c: JdbcTransaction, actor: Principal, affinity: RequestAffinity, provider: String, model: String, now: Long): List<String> {
        val exact = (Hints.requestedModel eq affinity.requestedModel) and (Hints.routeKey eq affinity.routeKey) and (Hints.resolvedModel eq model)
        return Hints.select(Hints.accountId).where {
            (Hints.teamId eq actor.teamId) and (Hints.memberId eq actor.member.id) and (Hints.conversationKey eq affinity.conversationKey) and
                (Hints.provider eq provider) and (Hints.expiresAt greater now)
        }.orderBy(exact to SortOrder.DESC, Hints.lastSeenAt to SortOrder.DESC, Hints.accountId to SortOrder.ASC).map { it[Hints.accountId] }.distinct()
    }
    fun loads(c: JdbcTransaction, teamId: String, now: Long): Map<String, Int> {
        val conversations = Hints.select(Hints.accountId, Hints.memberId, Hints.conversationKey)
            .where { (Hints.teamId eq teamId) and (Hints.lastSeenAt greater now - ACTIVE_TTL) }.withDistinct().alias("active_conversations")
        val account = conversations[Hints.accountId]
        val count = account.count()
        return conversations.select(account, count).groupBy(account).associate { it[account] to it[count].toInt() }
    }

    fun record(c: JdbcTransaction, actor: Principal, affinity: RequestAffinity, target: RouteTarget, accountId: String, now: Long) {
        val updated = Hints.update({ key(actor, affinity) }) {
            it[wireProtocol] = affinity.protocol.name; it[requestedEffort] = affinity.effort; it[thinkingTokens] = affinity.thinkingTokens
            it[provider] = target.provider; it[resolvedModel] = requireNotNull(target.model); it[Hints.accountId] = accountId
            it[lastSeenAt] = now; it[expiresAt] = now + KNOWN_TTL
        }
        if (updated != 0) return
        // At capacity, admission still works without evicting another member's active conversation.
        if (Hints.selectAll().where { Hints.teamId eq actor.teamId }.count() >= MAX_TEAM_HINTS ||
            Hints.selectAll().where { (Hints.teamId eq actor.teamId) and (Hints.memberId eq actor.member.id) }.count() >= MAX_MEMBER_HINTS) return
        Hints.insert {
            it[teamId] = actor.teamId; it[memberId] = actor.member.id; it[sessionKey] = affinity.sessionKey
            it[conversationKey] = affinity.conversationKey; it[requestedModel] = affinity.requestedModel; it[routeKey] = affinity.routeKey
            it[wireProtocol] = affinity.protocol.name; it[requestedEffort] = affinity.effort; it[thinkingTokens] = affinity.thinkingTokens
            it[provider] = target.provider; it[resolvedModel] = requireNotNull(target.model); it[Hints.accountId] = accountId
            it[lastSeenAt] = now; it[expiresAt] = now + KNOWN_TTL
        }
    }
    companion object {
        const val KNOWN_TTL = 3_600_000L
        const val ACTIVE_TTL = 120_000L
        const val MAX_TEAM_HINTS = 10_000
        const val MAX_MEMBER_HINTS = 1_000
    }
}
