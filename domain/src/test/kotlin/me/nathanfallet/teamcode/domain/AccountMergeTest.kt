package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import kotlin.test.*

class AccountMergeTest {
    private val identity = AccountIdentity("team", "anthropic", "account", "organization")
    private val existing = AccountRecord("stable-id", identity, "Operator name", 3, true, "reserved-pool", 7, AccountState.PENDING_HANDOVER)

    @Test fun `reenrollment preserves identity policy handover and custom name`() {
        val result = MergeAccountUseCase().merge(existing, VerifiedProviderAccount(identity, "New provider name"), 3)
        assertEquals(existing.copy(credentialVersion = 4), result)
    }
    @Test fun `same account in a different organization cannot replace existing credentials`() {
        assertFailsWith<IllegalArgumentException> {
            MergeAccountUseCase().merge(existing, VerifiedProviderAccount(identity.copy(organizationId = "other"), "Same email"), 3)
        }
    }
    @Test fun `late import cannot overwrite a concurrent refresh`() {
        assertFailsWith<CredentialVersionConflict> {
            MergeAccountUseCase().merge(existing, VerifiedProviderAccount(identity, "name"), 2)
        }
    }
    @Test fun `credential objects never expose secrets through debug rendering`() {
        val credentials = ProviderCredentials("access-secret", "refresh-secret", 1)
        assertEquals("ProviderCredentials([REDACTED])", credentials.toString())
    }
}
