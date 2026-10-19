package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.insert

/** Published identity schema before device registration. Never run current stores on an old schema. */
private object LegacyMembersBeforeDevices : Table("members") {
    val id = varchar("id", 36); val teamId = varchar("team_id", 36); val username = varchar("username", 64)
    val passwordHash = varchar("password_hash", 255); val role = varchar("role", 16)
    val disabled = bool("disabled"); val mustChangePassword = bool("must_change_password")
}
private object LegacyTokensBeforeDevices : Table("tokens") {
    val id = varchar("id", 36); val memberId = varchar("member_id", 36)
    val tokenHash = char("token_hash", 64); val device = varchar("device", 120); val expiresAt = long("expires_at")
}
internal suspend fun seedLegacyAdministrator(database: Database, password: String): LoginSession = database.query {
    val member = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val token = Passwords.secret()
    val expires = nowMillis() + 86_400_000
    LegacyMembersBeforeDevices.insert {
        it[LegacyMembersBeforeDevices.id] = member; it[teamId] = "default"; it[username] = "admin"
        it[passwordHash] = Passwords().hash(password); it[role] = "ADMIN"; it[disabled] = false; it[mustChangePassword] = false
    }
    LegacyTokensBeforeDevices.insert {
        it[LegacyTokensBeforeDevices.id] = id; it[memberId] = member; it[tokenHash] = Passwords.digest(token)
        it[device] = "Pre-upgrade CLI"; it[expiresAt] = expires
    }
    LoginSession(token, Principal(Member(member, "admin", Role.ADMIN, false, false), id, "default"), expires)
}
