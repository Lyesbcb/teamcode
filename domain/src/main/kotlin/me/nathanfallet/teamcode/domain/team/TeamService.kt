package me.nathanfallet.teamcode.domain.team

enum class Role { ADMIN, MEMBER }
data class Member(val id: String, val username: String, val role: Role, val disabled: Boolean, val passwordChangeRequired: Boolean)
data class Principal(val member: Member, val tokenId: String, val teamId: String)
class LoginSession(val token: String, val principal: Principal, val expiresAt: Long) {
    override fun toString() = "LoginSession([REDACTED])"
}
class NewMember(val member: Member, val temporaryPassword: String) {
    override fun toString() = "NewMember(member=$member, temporaryPassword=[REDACTED])"
}
class TeamFailure(val code: String, val status: Int, message: String) : Exception(message)

/** All authorization and last-administrator invariants belong here or in its transactional adapter. */
interface TeamService {
    suspend fun login(username: String, password: String, device: String, installationId: String? = null): LoginSession
    suspend fun authenticate(token: String): Principal?
    suspend fun logout(principal: Principal)
    suspend fun changePassword(principal: Principal, current: String, replacement: String)
    suspend fun members(principal: Principal): List<Member>
    suspend fun createMember(principal: Principal, username: String, role: Role): NewMember
    suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean): Member
    suspend fun resetPassword(principal: Principal, id: String): NewMember
}

fun Principal.requireAdmin() {
    if (member.role != Role.ADMIN) throw TeamFailure("forbidden", 403, "Administrator access is required.")
    requireReady()
}
fun Principal.requireReady() {
    if (member.passwordChangeRequired) throw TeamFailure("password_change_required", 403, "Change your temporary password first.")
    if (member.disabled) throw TeamFailure("forbidden", 403, "This member is disabled.")
}
