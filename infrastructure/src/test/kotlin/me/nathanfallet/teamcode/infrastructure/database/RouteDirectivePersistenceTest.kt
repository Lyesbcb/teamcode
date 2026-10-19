package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.policy.ModelPolicy
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.infrastructure.gateway.SessionAffinityKey
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class RouteDirectivePersistenceTest {
    @Test fun activeRoutesBoundGuidanceAndRecoverySurvivesReplicaChanges() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val password = team.bootstrap()!!
                val pending = team.login("admin", password, "forecast test")
                team.changePassword(pending.principal, password, "forecast replacement password")
                val actor = team.login("admin", "forecast replacement password", "forecast test").principal
                val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 29 }))
                suspend fun account(provider: String): AccountRecord {
                    val verified = VerifiedProviderAccount(AccountIdentity("default", provider, provider, "test-org"), provider)
                    val record = (accounts.upsert(verified, ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "forecast-$provider") as EnrollmentResult.Created).account
                    return accounts.handover("default", record.id, accessOnly = true)
                }
                val claude = account("anthropic")
                val codex = account("codex")
                val admission = SqlAdmissionStore(a)
                val routing = SqlRoutingStore(a)
                val policies = SqlModelPolicyStore(a)
                val controllerA = SqlDirectiveService(a)
                val controllerB = SqlDirectiveService(b)
                suspend fun observations(account: AccountRecord, values: List<Double>, extra: Boolean = false, age: Long = 0) {
                    a.transaction { it.execute("DELETE FROM quota_history WHERE account_id = ?", account.id) }
                    val now = a.transaction { it.nowMillis() }
                    val windows = if (extra) listOf("five_hour", "seven_day", "model:fable:seven_day") else listOf("five_hour", "seven_day")
                    admission.observe(account.id, windows.flatMap { window -> values.mapIndexed { index, value ->
                        AccountQuota(account.id, window, if (window.startsWith("model:")) .99 else value, now - age - 120_000 + index * 60_000, now + 3_600_000)
                    } })
                }
                suspend fun context(account: AccountRecord, requested: String = "claude-opus-test", resolved: String = requested, effort: String? = null) {
                    val protocol = if (requested.startsWith("claude")) WireProtocol.MESSAGES else WireProtocol.RESPONSES
                    val targets = ResolveRouteUseCase().resolve(routing.list("default"), requested, protocol)
                    var body = Json.parseToJsonElement("""{"messages":[{"role":"user","content":"synthetic"}],"input":"synthetic"}""").jsonObject
                    if (effort != null) body = kotlinx.serialization.json.JsonObject(body + ((if (protocol == WireProtocol.MESSAGES) "output_config" else "reasoning") to kotlinx.serialization.json.buildJsonObject { put("effort", kotlinx.serialization.json.JsonPrimitive(effort)) }))
                    val hint = SessionAffinityKey.from(mapOf("session-id" to "test-session"), body, protocol, requested, targets)!!
                    a.transaction { c ->
                        c.lockTeam("default")
                        c.execute("DELETE FROM conversation_affinities WHERE member_id = ?", actor.member.id)
                        SqlSessionAffinity().record(c, actor, hint, RouteTarget(account.identity.provider, resolved), account.id, c.nowMillis())
                    }
                }
                suspend fun pace(controller: SqlDirectiveService = controllerB): AgentDirective {
                    controller.update()
                    return controller.current(actor)!!
                }
                observations(claude, listOf(.1, .1, .1), extra = true)
                observations(codex, listOf(.1, .1, .1))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Unrelated idle subscriptions are not an active workload")
                context(claude)
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace, "A forbidden Fable window must not pressure Opus")
                observations(claude, listOf(.1, .2, .3))
                val slow = pace(controllerA)
                assertEquals(Pace.SLOW_DOWN, slow.advice.pace, "Idle Codex cannot hide active Claude burn")
                observations(claude, listOf(.1, .1, .1))
                assertEquals(slow.id, pace().id, "Recovery does not immediately bounce back to acceleration")
                val started = a.transaction { it.rows("SELECT recovery_started_at FROM directives WHERE member_id = ?", actor.member.id) { row -> row.getLong(1) }.single() }
                assertTrue(started > 0)
                assertEquals(slow.id, pace(controllerA).id, "Another replica resumes the same pending recovery")
                a.transaction { it.execute("UPDATE directives SET recovery_started_at = recovery_started_at - 120001 WHERE member_id = ?", actor.member.id) }
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace)

                observations(claude, listOf(.99, .99, .99))
                assertEquals(Pace.PAUSE, pace().advice.pace)
                val route = routing.save(actor, RouteRule("fallback", "claude-*", listOf(RouteTarget("codex", "gpt-6-astra"))))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "A route edit invalidates the old workload context immediately")
                context(codex, resolved = "gpt-6-astra")
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace, "Actual fallback activity uses Codex observations")
                policies.save(actor, ModelPolicy(deniedModels = listOf("*fable*", "gpt-*")))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Forbidden resolved models cannot advertise capacity")
                policies.save(actor, ModelPolicy())
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace)
                policies.save(actor, ModelPolicy(allowedEfforts = listOf("low")))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "A denied previously used effort cannot advertise capacity")
                context(codex, resolved = "gpt-6-astra", effort = "low")
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace)
                policies.save(actor, ModelPolicy())
                accounts.configure("default", codex.id, "Codex", false, 0, "private")
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Another pool's idle quota is inaccessible")
                accounts.configure("default", codex.id, "Codex", false, 0)
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace)
                observations(codex, listOf(.1, .1, .1), age = 400_000)
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Hysteresis never retains stale optimistic guidance")
                routing.delete(actor, route.id, route.version)
                observations(codex, listOf(.1, .1, .1))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Deleted fallback routes cannot inherit a provider pin")
                context(codex, "gpt-6-astra")
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace, "Native Responses activity retains its original protocol")
                a.transaction { it.execute("UPDATE conversation_affinities SET last_seen_at = 0") }
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Inactive hints do not advertise headroom")
                context(codex, "gpt-6-astra")
                admission.configurePool(actor, PoolBudget("", 1))
                val spent = admission.reserve(actor, 1, "gpt-6-astra", "codex")
                admission.settle(spent, 1)
                val exhausted = pace()
                assertEquals(Pace.PAUSE, exhausted.advice.pace)
                assertTrue(exhausted.advice.message.contains("allowance"))
                admission.configurePool(actor, PoolBudget("", 1_000_000))
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace, "A changed budget invalidates the allowance pause")
                admission.configure(actor, MemberPolicy(actor.member.id, weight = 0))
                assertEquals(Pace.PAUSE, pace().advice.pace, "Zero weighted share cannot advertise acceleration")
            } }
        }
    }
}
