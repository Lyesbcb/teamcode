package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.AccountQuota
import me.nathanfallet.teamcode.domain.gateway.WireProtocol

/** Fixed provider destinations; no end-user URL can receive a subscription credential. */
interface ProviderTransport {
    val provider: String
    val protocol: WireProtocol
    suspend fun refresh(previous: ProviderCredentials): ProviderCredentials
    suspend fun usage(account: AccountRecord, credentials: ProviderCredentials): List<AccountQuota>
    suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit)
    fun quotas(accountId: String, headers: Headers, now: Long): List<AccountQuota>
}
