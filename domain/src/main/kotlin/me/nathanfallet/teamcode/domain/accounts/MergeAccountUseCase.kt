package me.nathanfallet.teamcode.domain.accounts

/** Policy-preserving credential update, to be invoked under the repository's transaction/row lock. */
class MergeAccountUseCase {
    fun merge(existing: AccountRecord, verified: VerifiedProviderAccount, expectedVersion: Long): AccountRecord {
        require(existing.identity == verified.identity) { "Provider identity does not match the stored account." }
        if (existing.credentialVersion != expectedVersion) throw CredentialVersionConflict()
        require(existing.credentialVersion < Long.MAX_VALUE) { "Credential version limit reached." }
        // Display name, disabled state, pool, priority and handover state are operator-owned.
        return existing.copy(credentialVersion = existing.credentialVersion + 1)
    }
}
