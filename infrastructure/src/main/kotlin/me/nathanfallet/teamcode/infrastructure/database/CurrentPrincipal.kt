package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

internal fun requireCurrent(c: JdbcTransaction, actor: Principal) {
    val role = (Tokens innerJoin Members).select(Members.role).where {
        (Tokens.id eq actor.tokenId) and (Members.id eq actor.member.id) and (Members.teamId eq actor.teamId) and
            (Members.disabled eq false) and (Members.mustChangePassword eq false) and (Tokens.expiresAt greater c.nowMillis())
    }.singleOrNull()?.get(Members.role)
    if (role == null || role != actor.member.role.name) throw TeamFailure("authentication_required", 401, "Sign in to TeamCode again.")
}
