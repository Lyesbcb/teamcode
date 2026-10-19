package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object RequestCancellations : Table("request_cancellations") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val requestId = varchar("request_id", 36)
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(teamId, memberId, requestId)
    init {
        index("cancellation_expiry", false, expiresAt)
        index("cancellation_member_expiry", false, teamId, memberId, expiresAt)
        index("cancellation_team_expiry", false, teamId, expiresAt)
    }
}
