package me.nathanfallet.teamcode.infrastructure.database

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords

class SqlTeamService(private val database: Database, private val passwords: Passwords = Passwords()) : TeamService {
    private val hashing = Semaphore(4)
    private val dummyHash = passwords.hash(Passwords.secret())

    /** Returns the password only for the transaction that creates the first administrator. */
    suspend fun bootstrap(): String? = database.transaction { c ->
        c.lockTeam("default")
        if (c.rows("SELECT id FROM members WHERE team_id = ? LIMIT 1", "default") { it.getString(1) }.isNotEmpty()) return@transaction null
        val password = Passwords.secret()
        c.execute("INSERT INTO members (id, team_id, username, password_hash, role) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), "default", "admin", passwords.hash(password), "ADMIN")
        password
    }

    override suspend fun login(username: String, password: String, device: String): LoginSession {
        if (username.length !in 1..64 || password.length !in 1..1024) throw denied()
        val outcome = hashing.withPermit { database.transaction { c ->
            val row = c.rows("SELECT * FROM members WHERE team_id = ? AND username = ? FOR UPDATE", "default", username.lowercase()) { r ->
                Triple(member(r), r.getString("password_hash"), r.getLong("locked_until"))
            }.singleOrNull()
            val now = System.currentTimeMillis()
            if (row != null && row.third > now) return@transaction null
            val valid = passwords.verify(password, row?.second ?: dummyHash)
            if (row == null) return@transaction null
            if (!valid || row.first.disabled) {
                c.execute("UPDATE members SET failed_logins = failed_logins + 1 WHERE id = ?", row.first.id)
                c.execute("UPDATE members SET locked_until = CASE WHEN failed_logins >= 5 THEN ? ELSE 0 END WHERE id = ?", now + 60_000, row.first.id)
                return@transaction null
            }
            c.execute("UPDATE members SET failed_logins = 0, locked_until = 0 WHERE id = ?", row.first.id)
            // Bound persistent session growth even for an authenticated caller.
            c.execute("DELETE FROM tokens WHERE member_id = ? AND expires_at < ?", row.first.id, now)
            val count = c.rows("SELECT COUNT(*) FROM tokens WHERE member_id = ?", row.first.id) { it.getInt(1) }.single()
            if (count >= 50) c.execute("DELETE FROM tokens WHERE member_id = ? ORDER BY expires_at ASC LIMIT 1", row.first.id)
            val token = Passwords.secret()
            val id = UUID.randomUUID().toString()
            val expires = now + if (device == "Web dashboard") 8 * 3_600_000L else 30 * 86_400_000L
            c.execute("INSERT INTO tokens (id, member_id, token_hash, device, expires_at) VALUES (?, ?, ?, ?, ?)", id, row.first.id, Passwords.digest(token), device.filter { !it.isISOControl() }.take(120), expires)
            c.audit("default", row.first.id, "session.login", id)
            LoginSession(token, Principal(row.first, id, "default"), expires)
        } }
        return outcome ?: throw denied()
    }
    override suspend fun authenticate(token: String): Principal? {
        if (token.length !in 32..256) return null
        return database.transaction { c -> c.rows(
            "SELECT m.*, t.id AS token_id FROM tokens t JOIN members m ON m.id = t.member_id WHERE t.token_hash = ? AND t.expires_at > ? AND m.disabled = FALSE",
            Passwords.digest(token), System.currentTimeMillis(), map = ::principal,
        ).singleOrNull() }
    }
    override suspend fun logout(principal: Principal) { database.transaction { it.execute("DELETE FROM tokens WHERE id = ? AND member_id = ?", principal.tokenId, principal.member.id); Unit } }
    override suspend fun changePassword(principal: Principal, current: String, replacement: String) {
        if (replacement.length !in 14..1024 || replacement == current) throw TeamFailure("invalid_password", 400, "Choose a different password with at least 14 characters (up to 1024).")
        hashing.withPermit { database.transaction { c ->
            c.lockTeam(principal.teamId)
            val actor = fresh(c, principal)
            val hash = c.rows("SELECT password_hash FROM members WHERE id = ? FOR UPDATE", actor.member.id) { it.getString(1) }.single()
            if (!passwords.verify(current, hash)) throw denied()
            c.execute("UPDATE members SET password_hash = ?, must_change_password = FALSE, failed_logins = 0, locked_until = 0 WHERE id = ?", passwords.hash(replacement), actor.member.id)
            c.execute("DELETE FROM tokens WHERE member_id = ?", actor.member.id)
            c.audit(actor.teamId, actor.member.id, "member.password_changed", actor.member.id)
        } }
    }
    override suspend fun members(principal: Principal): List<Member> = database.transaction { c ->
        fresh(c, principal).requireAdmin()
        c.rows("SELECT * FROM members WHERE team_id = ? ORDER BY username LIMIT 1000", principal.teamId, map = ::member)
    }
    override suspend fun createMember(principal: Principal, username: String, role: Role): NewMember {
        val normalized = username.lowercase()
        if (!Regex("[a-z0-9][a-z0-9._-]{2,63}").matches(normalized)) throw TeamFailure("invalid_username", 400, "Use 3–64 letters, digits, dots, underscores or hyphens.")
        return hashing.withPermit { database.transaction { c ->
            c.lockTeam(principal.teamId)
            fresh(c, principal).requireAdmin()
            if (c.rows("SELECT id FROM members WHERE team_id = ? AND username = ?", principal.teamId, normalized) { it.getString(1) }.isNotEmpty()) throw TeamFailure("username_exists", 409, "This username already exists.")
            val count = c.rows("SELECT COUNT(*) FROM members WHERE team_id = ?", principal.teamId) { it.getInt(1) }.single()
            if (count >= 1000) throw TeamFailure("member_limit", 409, "The member limit has been reached.")
            val id = UUID.randomUUID().toString()
            val password = Passwords.secret()
            c.execute("INSERT INTO members (id, team_id, username, password_hash, role) VALUES (?, ?, ?, ?, ?)", id, principal.teamId, normalized, passwords.hash(password), role.name)
            c.audit(principal.teamId, principal.member.id, "member.created", id)
            NewMember(Member(id, normalized, role, false, true), password)
        } }
    }
    override suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean): Member = database.transaction { c ->
        c.lockTeam(principal.teamId)
        fresh(c, principal).requireAdmin()
        val target = getMember(c, principal.teamId, id)
        if (target.role == Role.ADMIN && !target.disabled && (role != Role.ADMIN || disabled)) {
            val admins = c.rows("SELECT COUNT(*) FROM members WHERE team_id = ? AND role = 'ADMIN' AND disabled = FALSE", principal.teamId) { it.getInt(1) }.single()
            if (admins <= 1) throw TeamFailure("last_admin", 409, "The last active administrator cannot be disabled or demoted.")
        }
        c.execute("UPDATE members SET role = ?, disabled = ? WHERE id = ?", role.name, disabled, id)
        c.execute("DELETE FROM tokens WHERE member_id = ?", id)
        c.audit(principal.teamId, principal.member.id, "member.updated", id)
        target.copy(role = role, disabled = disabled)
    }
    override suspend fun resetPassword(principal: Principal, id: String): NewMember = hashing.withPermit { database.transaction { c ->
        c.lockTeam(principal.teamId)
        fresh(c, principal).requireAdmin()
        val target = getMember(c, principal.teamId, id)
        val password = Passwords.secret()
        c.execute("UPDATE members SET password_hash = ?, must_change_password = TRUE, failed_logins = 0, locked_until = 0 WHERE id = ?", passwords.hash(password), id)
        c.execute("DELETE FROM tokens WHERE member_id = ?", id)
        c.audit(principal.teamId, principal.member.id, "member.password_reset", id)
        NewMember(target.copy(passwordChangeRequired = true), password)
    } }
    private fun getMember(c: Connection, team: String, id: String): Member = c.rows("SELECT * FROM members WHERE team_id = ? AND id = ?", team, id, map = ::member).singleOrNull()
        ?: throw TeamFailure("not_found", 404, "Member not found.")
    private fun fresh(c: Connection, actor: Principal): Principal = c.rows(
        "SELECT m.*, t.id AS token_id FROM tokens t JOIN members m ON m.id = t.member_id WHERE t.id = ? AND m.team_id = ? AND t.expires_at > ? AND m.disabled = FALSE",
        actor.tokenId, actor.teamId, System.currentTimeMillis(), map = ::principal,
    ).singleOrNull() ?: throw denied()
    private fun principal(r: ResultSet) = Principal(member(r), r.getString("token_id"), r.getString("team_id"))
    private fun member(r: ResultSet) = Member(r.getString("id"), r.getString("username"), Role.valueOf(r.getString("role")), r.getBoolean("disabled"), r.getBoolean("must_change_password"))
    private fun denied() = TeamFailure("invalid_credentials", 401, "Invalid credentials or temporarily locked account.")
}
