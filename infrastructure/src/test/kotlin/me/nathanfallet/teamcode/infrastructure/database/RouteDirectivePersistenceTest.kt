package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.policy.ModelPolicy
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.infrastructure.gateway.SessionAffinityKey
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
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
                    a.query { QuotaHistory.deleteWhere { accountId eq account.id } }
                    val now = a.query { nowMillis() }
                    val windows = if (extra) listOf("five_hour", "seven_day", "model:fable:seven_day") else listOf("five_hour", "seven_day")
                    // Use the same clock as the database's future-observation guard. The
                    // host clock can run slightly ahead of the disposable MySQL VM.
                    admission.observe(account.id, QuotaUpdate(windows.flatMap { window -> values.mapIndexed { index, value ->
                        AccountQuota(account.id, window, if (window.startsWith("model:")) .99 else value, now - age - 120_000 + index * 60_000, now + 3_600_000)
                    } }, now))
                    assertEquals(values.size * 2L, a.query {
                        QuotaHistory.selectAll().where {
                            (QuotaHistory.accountId eq account.id) and (QuotaHistory.windowName inList listOf("five_hour", "seven_day"))
                        }.count()
                    }, "Every synthetic shared-window sample must be persisted before evaluating guidance")
                }
                suspend fun context(account: AccountRecord, requested: String = "claude-opus-test", resolved: String = requested, effort: String? = null, session: String = "test-session", retain: Boolean = false): RequestAffinity {
                    val protocol = if (requested.startsWith("claude")) WireProtocol.MESSAGES else WireProtocol.RESPONSES
                    val targets = ResolveRouteUseCase().resolve(routing.list("default"), requested, protocol)
                    var body = Json.parseToJsonElement("""{"messages":[{"role":"user","content":"synthetic"}],"input":"synthetic"}""").jsonObject
                    if (effort != null) body = kotlinx.serialization.json.JsonObject(body + ((if (protocol == WireProtocol.MESSAGES) "output_config" else "reasoning") to kotlinx.serialization.json.buildJsonObject { put("effort", kotlinx.serialization.json.JsonPrimitive(effort)) }))
                    val hint = SessionAffinityKey.from(mapOf("session-id" to session), body, protocol, requested, targets)!!
                    a.query {
                    val c = this
                        c.lockTeam("default")
                        if (!retain) ConversationAffinities.deleteWhere { memberId eq actor.member.id }
                        SqlSessionAffinity().record(c, actor, hint, RouteTarget(account.identity.provider, resolved), account.id, c.nowMillis())
                    }
                    return hint
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
                val started = a.query { Directives.select(Directives.recoveryStartedAt).where { (Directives.scopeKey eq "") and (Directives.memberId eq actor.member.id) }.single()[Directives.recoveryStartedAt]!! }
                assertTrue(started > 0)
                assertEquals(slow.id, pace(controllerA).id, "Another replica resumes the same pending recovery")
                a.query { Directives.update({ Directives.memberId eq actor.member.id }) { it[recoveryStartedAt] = Directives.recoveryStartedAt - 120001 } }
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace)

                observations(claude, listOf(.99, .99, .99))
                assertEquals(Pace.PAUSE, pace().advice.pace)
                val route = routing.save(actor, RouteRule("fallback", "claude-*", listOf(RouteTarget("codex", "gpt-6-astra"))))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "A route edit invalidates the old workload context immediately")
                context(codex, resolved = "gpt-6-astra")
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Disabled translation cannot advertise cross-provider capacity")
                policies.save(actor, ModelPolicy(experimentalCrossProviderTranslation = true))
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace, "Actual enabled fallback activity uses Codex observations")
                policies.save(actor, ModelPolicy())
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Turning the flag off invalidates existing translated activity")
                policies.save(actor, ModelPolicy(experimentalCrossProviderTranslation = true))
                policies.save(actor, ModelPolicy(experimentalCrossProviderTranslation = true, deniedModels = listOf("*fable*", "gpt-*")))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "Forbidden resolved models cannot advertise capacity")
                policies.save(actor, ModelPolicy(experimentalCrossProviderTranslation = true))
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace)
                policies.save(actor, ModelPolicy(experimentalCrossProviderTranslation = true, allowedEfforts = listOf("low")))
                assertEquals(Pace.UNKNOWN, pace().advice.pace, "A denied previously used effort cannot advertise capacity")
                context(codex, resolved = "gpt-6-astra", effort = "low")
                assertEquals(Pace.CAN_INCREASE, pace().advice.pace)
                policies.save(actor, ModelPolicy(experimentalCrossProviderTranslation = true))
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
                // Admission and guidance share provider-observed model aliases across replicas.
                val meteredAt = a.query { nowMillis() }
                val modelCap = AccountQuota(codex.id, "model:premium:seven_day", 1.0, meteredAt - 1, meteredAt + 3_600_000)
                admission.observe(codex.id, QuotaUpdate(listOf(modelCap), meteredAt - 1, modelLimit = ModelLimitObservation("gpt-6-astra", "premium")))
                assertEquals(Pace.PAUSE, pace().advice.pace, "A canonical limit alias must constrain the active model's forecast")
                SqlAdmissionStore(b).observe(codex.id, QuotaUpdate(emptyList(), meteredAt, replaceModelWindows = true))
                pace() // Start conservative recovery; retired history must not recreate the cap.
                a.query { Directives.update({ Directives.memberId eq actor.member.id }) { it[recoveryStartedAt] = Directives.recoveryStartedAt - 120001 } }
                assertEquals(Pace.CAN_INCREASE, pace(controllerA).advice.pace, "Retired model-cap history must not continue pausing guidance")
                observations(claude, listOf(.1, .1, .1))
                context(claude)
                val claudeAt = a.query { nowMillis() }
                admission.observe(claude.id, listOf(AccountQuota(claude.id, "model:opus", 1.0, claudeAt - 1, claudeAt + 3_600_000)))
                assertEquals(Pace.PAUSE, pace().advice.pace)
                SqlAdmissionStore(b).observe(claude.id, ClaudeTransport.usageSnapshot(claude.id, Json.parseToJsonElement("""{"limits":[]}""").jsonObject, claudeAt))
                pace()
                a.query { Directives.update({ Directives.memberId eq actor.member.id }) { it[recoveryStartedAt] = Directives.recoveryStartedAt - 120001 } }
                assertEquals(Pace.CAN_INCREASE, pace(controllerA).advice.pace, "Claude cap retirement must also release forecast pressure after hysteresis")
                a.query { ConversationAffinities.update { it[lastSeenAt] = 0 } }
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

                admission.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 4))
                observations(claude, listOf(.1, .2, .3))
                observations(codex, listOf(.1, .1, .1))
                // The previous .99 observation is a different synthetic scenario; clear its admission snapshot too.
                a.query { QuotaWindows.deleteWhere { accountId eq claude.id } }
                observations(claude, listOf(.1, .2, .3))
                val slowHint = context(claude, session = "slow-session")
                val fastHint = context(codex, "gpt-6-astra", session = "fast-session", retain = true)
                assertEquals(Pace.SLOW_DOWN, pace().advice.pace)
                assertEquals(1, a.query { Directives.selectAll().where { Directives.memberId eq actor.member.id }.count().toInt() }, "Legacy readers still see one member directive during rolling upgrades")
                assertEquals(2, a.query { SessionDirectives.selectAll().where { (SessionDirectives.memberId eq actor.member.id) and (SessionDirectives.scopeKey inList listOf(slowHint.sessionKey, fastHint.sessionKey)) }.count().toInt() })
                assertEquals(Pace.SLOW_DOWN, controllerA.current(actor, "slow-session")!!.advice.pace)
                assertEquals(Pace.CAN_INCREASE, controllerB.current(actor, "fast-session")!!.advice.pace)
                assertNull(controllerA.current(actor, "absent-session"))
                val slowRequest = admission.reserve(actor, 100, "claude-opus-test", affinity = slowHint)
                val freshSession = fastHint.copy(sessionKey = "e".repeat(64), conversationKey = "f".repeat(64))
                val firstTurn = admission.reserve(actor, 100, "gpt-6-astra", "codex", affinity = freshSession)
                admission.settle(firstTurn, 0)
                val fastRequests = (1..2).map { admission.reserve(actor, 100, "gpt-6-astra", "codex", affinity = fastHint) }
                assertEquals("pacing_delay", assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> {
                    admission.reserve(actor, 100, "claude-opus-test", affinity = slowHint)
                }.code)
                admission.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 3))
                assertEquals("member_concurrency", assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> {
                    admission.reserve(actor, 100, "gpt-6-astra", "codex", affinity = fastHint)
                }.code, "Per-session advice never bypasses the member's hard concurrency limit")
                admission.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 4))
                (fastRequests + slowRequest).forEach { admission.settle(it, 0) }

                val colleague = team.createMember(actor, "guidance-colleague", me.nathanfallet.teamcode.domain.team.Role.MEMBER)
                val firstLogin = team.login("guidance-colleague", colleague.temporaryPassword, "test")
                team.changePassword(firstLogin.principal, colleague.temporaryPassword, "guidance colleague replacement")
                val other = team.login("guidance-colleague", "guidance colleague replacement", "test").principal
                assertNull(controllerB.current(other, "slow-session"), "Session identifiers never grant another member's guidance")
                assertNull(controllerB.claim(other, "slow-session", "EMITTED_TO_HOOK"))
                val stage = "EMITTED_TO_HOOK"
                val competing = coroutineScope { listOf(controllerA, controllerB).map { controller -> async { controller.claim(actor, "slow-session", stage) } }.awaitAll() }
                val delivered = competing.filterNotNull().single()
                controllerB.acknowledge(actor, delivered.directive.id, stage, "slow-session", delivered.claimId)
                assertNull(controllerA.claim(actor, "slow-session", stage), "Acknowledged versions are suppressed across replicas")
                assertNotNull(controllerB.claim(actor, "slow-session", "RECEIVED_BY_CLI"), "Watching does not consume the hook delivery stage")
                val lost = controllerA.claim(actor, "fast-session", stage)!!
                a.query { DirectiveDeliveries.update({ DirectiveDeliveries.claimId eq lost.claimId }) { it[claimedUntil] = 0 } }
                val retry = controllerB.claim(actor, "fast-session", stage)!!
                assertNotEquals(lost.claimId, retry.claimId)
                assertEquals("claim_expired", assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> {
                    controllerA.acknowledge(actor, lost.directive.id, stage, "fast-session", lost.claimId)
                }.code)
                controllerA.acknowledge(actor, retry.directive.id, stage, "fast-session", retry.claimId)
                assertNull(controllerB.claim(actor, "fast-session", stage))
                assertEquals("invalid_session", assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> { controllerA.claim(actor, "bad session", stage) }.code)
                a.query { ConversationAffinities.update { it[lastSeenAt] = 0 } }
                assertNull(controllerB.current(actor, "fast-session"), "An inactive session cannot receive old acceleration")
                team.logout(actor)
                assertEquals("authentication_required", assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> { controllerB.current(actor, "slow-session") }.code)

            } }
        }
    }
}
