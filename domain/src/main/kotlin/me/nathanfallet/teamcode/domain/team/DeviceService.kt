package me.nathanfallet.teamcode.domain.team

data class Device(
    val id: String, val memberId: String, val name: String,
    val createdAt: Long, val lastLoginAt: Long, val activeSessions: Int, val current: Boolean,
)
data class MemberSession(
    val id: String, val memberId: String, val name: String, val deviceId: String?,
    val createdAt: Long?, val expiresAt: Long, val current: Boolean,
)

/** Session metadata only. Credential hashes and installation identifiers never leave persistence. */
interface DeviceService {
    suspend fun devices(actor: Principal, memberId: String = actor.member.id): List<Device>
    suspend fun sessions(actor: Principal, memberId: String = actor.member.id): List<MemberSession>
    suspend fun rename(actor: Principal, deviceId: String, name: String): Device
    /** Unregisters the device and revokes all its sessions; returns the affected member ID. */
    suspend fun revokeDevice(actor: Principal, deviceId: String): String
    suspend fun revokeSession(actor: Principal, sessionId: String): String

    companion object {
        const val DEVICE_LIMIT = 50L
        private val installationPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun validateInstallation(id: String?) {
            if (id != null && !installationPattern.matches(id))
                throw TeamFailure("invalid_installation", 400, "An installation identifier must be a lowercase UUID.")
        }
        fun validateName(name: String) {
            if (name.isBlank() || name.length > 120 || name.any(Char::isISOControl))
                throw TeamFailure("invalid_device_name", 400, "Use a device name of 1–120 characters without control characters.")
        }
    }
}
