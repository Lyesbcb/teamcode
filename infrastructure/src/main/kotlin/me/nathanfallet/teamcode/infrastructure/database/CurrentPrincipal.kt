package me.nathanfallet.teamcode.infrastructure.database

import java.sql.Connection
import me.nathanfallet.teamcode.domain.team.*

internal fun requireCurrent(c: Connection, actor: Principal) {
    val row = c.rows("SELECT m.role FROM tokens t JOIN members m ON m.id = t.member_id WHERE t.id = ? AND m.id = ? AND m.team_id = ? AND m.disabled = FALSE AND m.must_change_password = FALSE AND t.expires_at > ?", actor.tokenId, actor.member.id, actor.teamId, c.nowMillis()) { it.getString(1) }.singleOrNull()
    if (row == null || row != actor.member.role.name) throw TeamFailure("authentication_required", 401, "Sign in to TeamCode again.")
}
