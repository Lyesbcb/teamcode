package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.gateway.RequestCancellationService
import me.nathanfallet.teamcode.domain.gateway.RequestCancellationStore
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.database.tables.RequestCancellations
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Cancellation may precede dispatch. Keeping a tombstone avoids registration races. */
class SqlRequestCancellationStore(private val database: Database) : RequestCancellationStore {
    override suspend fun cancel(actor: Principal, requestId: String) = database.query {
        lockTeam(actor.teamId)
        requireCurrent(this, actor)
        val now = nowMillis()
        RequestCancellations.deleteWhere { (teamId eq actor.teamId) and (expiresAt lessEq now) }
        val scope = (RequestCancellations.teamId eq actor.teamId) and (RequestCancellations.memberId eq actor.member.id)
        // Idempotence does not extend retention or reveal whether a live request exists.
        if (RequestCancellations.selectAll().where { scope and (RequestCancellations.requestId eq requestId) }.empty()) {
            val memberCount = RequestCancellations.selectAll().where { scope }.count()
            val teamCount = RequestCancellations.selectAll().where { RequestCancellations.teamId eq actor.teamId }.count()
            if (memberCount >= RequestCancellationService.MEMBER_LIMIT || teamCount >= RequestCancellationService.TEAM_LIMIT)
                throw TeamFailure("cancellation_limit", 503, "The bounded cancellation store is full. Retry shortly.")
            RequestCancellations.insert {
                it[teamId] = actor.teamId; it[memberId] = actor.member.id
                it[RequestCancellations.requestId] = requestId
                it[expiresAt] = now + RequestCancellationService.RETENTION_MILLIS
            }
        }
        Unit
    }

    override suspend fun isCancelled(actor: Principal, requestId: String): Boolean = database.query {
        val now = nowMillis()
        // A revoked original credential cannot deliver a control call. Stop its active
        // work on the next poll rather than relying on that credential for cleanup.
        requireCurrent(this, actor, now)
        !RequestCancellations.select(RequestCancellations.requestId).where {
            (RequestCancellations.teamId eq actor.teamId) and (RequestCancellations.memberId eq actor.member.id) and
                (RequestCancellations.requestId eq requestId) and (RequestCancellations.expiresAt greater now)
        }.empty()
    }

    override suspend fun maintain() = database.query {
        val now = nowMillis()
        RequestCancellations.deleteWhere { expiresAt lessEq now }
        Unit
    }
}
