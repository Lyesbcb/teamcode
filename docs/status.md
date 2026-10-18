# Implementation status

## J0: first verified transport slice

Implemented:

- Seven standalone Gradle modules with `me.nathanfallet.teamcode` package/group and enforced production dependency boundaries.
- KMP API contracts and typed client; authenticated capabilities/status endpoints and bounded streaming transport.
- Explicit development-only fixture mode. It makes no provider requests and does not pretend to be a production account pool.
- Native CLI commands: `version`, `configure`, `status`, `proxy start`.
- Configurable self-hosted server origin; saved URL/authentication in XDG or `~/.config/teamcode/config.json`; atomic private POSIX writes.
- Authenticated loopback-only Messages proxy, request size limit, narrow header forwarding, streaming response and coroutine cancellation.
- Pure server-side forecast and in-memory allowance reference with concurrency/idempotency tests. The allowance reference is not wired into production admission and is not durable.
- Independent JVM/native CI and synthetic smoke tests.

Local evidence (2026-10-18): 25 JVM tests passed; macOS ARM64 native binary linked; actual native CLI -> local proxy -> JVM fixture returned progressive SSE; saved configuration permissions were 0700/0600. Tests used random synthetic credentials and isolated config. No real provider was called. Linux x64 also links locally; actual Linux runtime validation is delegated to CI.

Still planned, not advertised as working: real Claude Max pooling, durable quotas, predictive controller/agent integration, model translation, OS service installation and releases for all platforms. Windows private config is fail-closed until its DACL implementation is verified. See [roadmap](roadmap.md).

## Account enrollment groundwork

Implemented as independently tested building blocks, not yet an enabled enrollment API or CLI command:

- Bounded TeamClaude OAuth account parser with per-entry unsupported/invalid results; no proxy keys or source identity hints imported.
- Credential redaction and account+organization identity types.
- A credential merge use case preserving TeamCode identity, custom name, disabled state, pool, priority and handover status; stale versions are rejected.
- AES-256-GCM credential envelopes with random nonces and authenticated account/team context; wrong key/context or malformed ciphertext fails without exposing the input.
- SDK connection/idle timeouts, management deadlines and a regression test keeping upstream response lifetimes open until downstream streaming completes.

The database adapter, server-side provider identity verification and add/import/reconnect commands must land before this becomes actual account enrollment. In-memory records are not presented as durable team state.

## J1: persistent team and enrollment slice

Implemented:

- MySQL 8.4 / HikariCP / Flyway persistence, bounded transactional repositories, encrypted provider credentials.
- One-time first-run `admin` creation; temporary bootstrap password logged only by the creating startup. No public registration or email infrastructure.
- PBKDF2 password hashing, forced temporary-password change, expiring hashed session tokens, per-account login lockout and a bounded login rate.
- Admin-created members, role/disable controls, password reset, session revocation and last-active-admin protection.
- Server-rendered dashboard with same-origin form checks, CSRF tokens, HttpOnly/SameSite cookies, no-store responses and escaped content.
- Native `login` / `logout`, hidden password prompts and saved server/session configuration.
- `accounts add` local manual PKCE flow, `accounts import --from teamclaude [--config PATH] [--dry-run]`, `accounts list`, and explicit `accounts handover ID --confirm`.
- Server-side Claude profile/Max verification, identity-based upsert, version conflict checks, import operation idempotency, encrypted credentials, preserved operator metadata, and pending handover by default.

Verification: MySQL concurrency tests prove one bootstrap, persistent sessions, revocation, last-admin protection, concurrent enrollment conflict behavior, metadata preservation, encrypted storage and team isolation. HTTP tests cover authorization, temporary-password restrictions, absent registration, CSRF/origin enforcement and cookie/API separation. Provider tests use a mock engine; no real subscription has been connected in development or CI. macOS ARM64 CLI links with the account commands.

Still needed for J1 exit: actual Max inference and provider usage display, plus an opt-in real-provider acceptance run. The ordinary server currently exposes the synthetic gateway fixture, clearly reported as fixture mode, while the persistent management plane is available. OAuth code exchange is implemented against the protocol observed in TeamClaude; it has not been exercised against a real account.

End-to-end evidence (2026-10-18): `scripts/smoke-team.py` passed against its own MySQL 8.4 container, the packaged server and the macOS ARM64 native CLI. It verified one-time bootstrap, password replacement, dashboard member creation, password restrictions, persistent sessions after server restart, native logout/revocation and absence of ordinary passwords in logs. The test exposed and fixed a server startup coroutine deadlock that unit tests did not cover.

## J2–J4: Claude pool, weighted admission, policy and pacing slice

Implemented:

- Real streaming Claude Max transport, bounded provider requests, metadata-only request/settlement logs and usage settlement. Native request fields pass through without conversion.
- Atomic SQL reservations across member and pool daily budgets; weighted allocation, hard caps, concurrent-request limits and separate protected account pools. Conservative charging for interrupted/missing-usage requests; no replay after dispatch.
- Serialized refresh with a database row lock; interrupted refresh requires reconnect. Explicit access-only activation never invokes refresh, including at expiry.
- Provider usage sampling plus response-header observations, per-window reset-aware histories, forecasts and expiring member directives. Server admission applies guidance constraints.
- Model and effort policy editor/API, Fable denied by default, thinking-budget cap and rejection before provider dispatch.
- Dashboard capacity, pool budgets, model restrictions and guidance; native guidance watch/hook output with delivery receipts.

Real-provider evidence (2026-10-18): the native CLI imported five verified Claude Max accounts from an authorized TeamClaude configuration in access-only mode. Reimport preserved account IDs and advanced credential versions. A real Claude Code 2.1.302 process, isolated with bare mode/tools disabled, called `claude-haiku-4-5-20251001` through the native loopback proxy and central packaged server. Provider HTTP 200, expected short response, and 305 settled team credits were verified. Source configuration SHA-256 was unchanged; no provider refresh ran. The script removed its own test database and processes. The Codex and expired source entries were explicitly skipped, with nonzero partial-import exit status.

Local deterministic verification: JVM domain/infrastructure/presentation/CLI suites and native macOS ARM64 link passed. SQL tests include competing admissions, pool isolation, weighted 5/5/1 allocation, retained spend after policy edits, exactly-once settlement and concurrent refresh. Gateway tests include streaming usage, cancellation without replay and sanitized upstream errors.

Still outstanding: bounded pre-stream failover, session affinity, forecast charts/accuracy validation, sparse per-session hook delivery, Codex/OSS adapters, cross-protocol tool loops, service installation and Windows private configuration. These are not implied by the successful Haiku smoke test. The earlier J1 fixture-only limitation is superseded by this slice.

## Native Codex subscription slice

Implemented: Codex entries in native TeamClaude import/upsert, remote account/user verification against authenticated usage, isolated Codex refresh/usage/Responses transport, provider-filtered durable admission, native `/v1/responses` proxy and Responses usage settlement. Default unsaved model policies now include `gpt-*`; saved administrator policies are unchanged. Provider-native request bodies keep tool definitions and protocol-specific fields. Direct Codex OAuth `accounts add` and cross-provider translation are still pending.

Real-provider evidence (2026-10-18): six source accounts imported successfully (five Claude Max and one Codex; one expired entry rejected). The actual Codex CLI 0.163.0, using a temporary custom provider configuration, completed a short `gpt-6-luna` request through the native proxy and central subscription pool. HTTP 200, `response.completed`, expected reply and 18,001 settled team credits were observed. The live test exposed that the Codex subscription endpoint can omit Content-Type on a valid SSE response; this case is now covered by a deterministic regression test. No source credentials were refreshed.

Deterministic tests cover remote identity mismatch, provider credential isolation, unchanged tool definitions, required store=false, no double counting of cache/reasoning usage, distinct model/window quota scopes and provider-specific SQL account selection. The prior Claude pool commit `688a83f` passed JVM, native macOS and native Linux CI. Local MySQL tests were moved to ephemeral tmpfs after Docker disk exhaustion; all affected suites passed again without deleting unrelated data or stopping other services.

## Replica coordination, deployment and native launchers

Implemented:

- Database-backed login throttling, worker leases and polling order, reservation counts and completed-request totals. Database time governs durable leases, session expiry and budget windows. Local caches do not own team state.
- Crash-safe refresh intent committed before the provider call, versioned credential replacement and mandatory reconnect after uncertain rotation. Refresh no longer holds a database connection during a network request. This supersedes the earlier lock-across-refresh implementation.
- Public database-aware `/ready`, separate `/health`, HikariCP pools of eight connections per replica, bounded JDBC waits and configurable graceful drain.
- Helm chart with two application replicas, optional official MySQL 8.4 StatefulSet, external MySQL support, HPA, streaming ingress, persistent secrets and non-root containers. CI gates the AMD64/ARM64 Docker Hub image on JVM, native and Helm checks. The chart uses `nathanfallet/teamcode:latest` with `Always`; publishing does not itself restart pods.
- Ktor 3.6.0 with the universal `ktor-client-engine-defaults` dependency for native clients.
- Native `teamcode run claude -- <args>` and `teamcode run codex -- <args>` on macOS/Linux: ephemeral authenticated local proxy, literal arguments, inherited terminal/cwd, per-child environment, child exit status and owned-process cleanup. No personal agent configuration is rewritten.

Local verification (2026-10-18): concurrent MySQL integration tests passed for shared login budgets, one-time bootstrap, competing reservations, exactly-once settlement, credential rotation/import fencing and worker lease ownership. Two actual server processes passed `smoke-replicas.py`, including simultaneous startup, session sharing, process crash/restart, database outage/readiness recovery and cross-replica logout. JVM suites, native macOS ARM64 link/runtime/progressive-stream tests and Linux x64 cross-link passed. Helm render/lint/contract/schema checks passed for bundled and external database modes. No Kubernetes cluster was deployed.

The native launcher smoke verifies literal arguments (including shell metacharacters), stdin/stdout/stderr, concurrent proxies, exit/signal status and unchanged configuration. A real Claude Code process through `teamcode run` completed the tiny Haiku acceptance with 305 settled credits. A real Codex 0.163.0 process through `teamcode run` completed the tiny `gpt-6-luna` acceptance with HTTP 200, `response.completed` and 18,001 settled credits. Seven verified source accounts were imported and reimported without changing their IDs; source credentials were never refreshed. The test caught root-level Codex configuration being ignored by `exec --ignore-user-config`; launch overrides now follow user arguments in the leaf command scope, before a literal `--` delimiter. Native Windows process/config support, OS service installation, cross-provider routing/translation and releases are still outstanding.

## Explicit routes and initial cross-provider tool profile

Implemented: team-scoped versioned SQL route rules, administrator API/dashboard CRUD, typed client methods, bounded glob matching, requested/resolved model and effort checks, and provider-specific admission. Ordered fallback handles unavailable capacity, unusable credentials before dispatch and definite 401/403/429 rejection. It tries the preferred target's eligible accounts before the next target, with at most 64 distinct accounts and a ten-minute overall deadline. Exhausted eligible capacity returns 429; reaching the account bound returns 503 without claiming exhaustion. Member/team limits remain enforced. Ambiguous failures and committed streams are not replayed. Native fields/streams bypass conversion; cross-protocol requests use explicit conversation and SSE adapters.

Failover regression evidence: six rejected accounts followed by a seventh available account produce exactly one successful client response, without switching providers early. The 64-account safety bound returns 503. A real MySQL test with two independent connection pools verifies that zero-charge rejections do not trigger pacing and that excluded accounts cannot be selected again. Domain, infrastructure and presentation suites passed after these changes.

Local evidence (2026-10-18): the full JVM suites, macOS ARM64 native link and actual native transport smoke passed. SQL tests verify cross-replica route persistence, isolation and stale-version conflicts. HTTP tests verify route authorization and browser CSRF. Codec/gateway tests cover multiple tools, empty arguments, split UTF-8, usage/cache accounting, missing terminal usage, downstream cancellation, unsupported opaque content, preserved native fields, forbidden destinations, definite rejection and ambiguous failures.

Opt-in real-provider tool acceptance passed through the native proxy in both directions: Messages/Haiku → Codex GPT-6 Luna (169 and 121 settled credits) and Responses/Luna → Claude Haiku (two successful streamed turns). Each test received a `sum` call with exact JSON arguments, returned its result with the original call ID, and verified the final calculated answer. A real Anthropic HTTP 429 also exercised account cooldown, zero-charge settlement and fallback to another account. Source TeamClaude credentials were imported access-only and never refreshed or changed.

These tool-loop acceptances explicitly disable reasoning and permit the `none` effort in their isolated team policy. Earlier default-reasoning attempts correctly exposed an incompatible opaque reasoning item. Signed/encrypted reasoning replay, custom/built-in tool conversion, session/account affinity and a complete default-Claude-Code/default-Codex cross-provider coding task remain outstanding. Do not infer their compatibility from this initial profile. Codex subscription `response.metadata` events are now handled as transport metadata, with a deterministic regression test.

Deployment evidence: commit `64a5484` passed JVM, Helm and native macOS/Linux CI, then published the AMD64/ARM64 Docker Hub image. The `latest` manifest was verified directly; the chart defaults to that image with `Always`. No Kubernetes deployment was performed. The separately committed V2 roadmap covers centralized MCP/skill administration and is outside current V1 implementation scope.

## Native user service slice

Implemented macOS LaunchAgent and Linux systemd user registration, idempotent install/start/stop/restart/uninstall, manifest rendering, bounded logs, separate status checks, private local capabilities and authenticated reuse by `teamcode run`. Saved team tokens are reloaded per request; upstream outages keep the listener alive. Shutdown is bounded and waits for accepted streams and the old process before restart. Externally edited registrations are preserved.

Local evidence (2026-10-18): JVM CLI tests passed. The actual macOS ARM64 binary passed `smoke-service.py` with launchd, synthetic upstream, login replacement, offline recovery, native child reuse, stop during an active stream, restart and uninstall. This exposed Ktor Native replacing the service's signal handler at startup; the handler is now installed after startup, allowing both stream draining and exit of the outer command loop. Test registrations were isolated and removed. The same native service acceptance passed on Linux with its actual systemd user manager in [CI run 39173156701](https://github.com/nathanfallet/teamcode/actions/runs/39173156701), alongside native transport, agent-launch and persistent team tests. Windows, system installation before login, automated updates and actual reboot/logon acceptance remain outstanding.

## Windows native support (in progress, required for V1)

Windows is a V1 requirement for team members using Windows workstations. The private-file, secure-random and process-launch stubs have been replaced with Windows adapters: protected owner-only DACLs and atomic file replacement, BCrypt random tokens, Unicode configuration/environment paths and CreateProcess with explicitly inherited standard streams. Standard Claude/Codex npm installations launch their JavaScript entry point through Node directly, preserving literal arguments without interpreting a `.cmd` shell script.

The Windows executable cross-links locally and JVM argument-encoding tests pass. A dedicated Windows CI job builds and executes the native binary, inspects actual file/directory ACLs and exercises authentication, progressive streaming and both agent launchers with synthetic inputs. Its first execution failed during configuration, so runtime support is not yet accepted. Configuration failures now report sanitized operation identifiers to distinguish upstream verification from private-file stages without exposing raw exceptions or credentials.

Windows logon service registration is implemented using Task Scheduler, an interactive least-privilege principal, no Windows password and a private cooperative stop marker. Stop disables the task, drains accepted streams and waits for its running instances to exit; start re-enables logon execution. Unicode arguments are read through the Windows wide-character API. The expanded Windows smoke includes actual service registration, reuse, drainage, restart and uninstall; these Windows runtime checks remain pending. macOS service regression and JVM tests passed after adding this platform branch.

Windows diagnosis: the native configuration failure returned `ERROR_PATH_NOT_FOUND` for a Unicode path because Okio 3.17.0 still used ANSI file APIs. Okio is now pinned to 3.18.2, whose Windows implementation uses wide-character APIs for directory creation, metadata and file access. The Windows smoke also covers login with a forced password change, trusted/untrusted HTTPS and descendant cleanup. Launchers use a private Windows job assigned before the child is resumed; closing it terminates only that launch's descendants. Runtime acceptance of these changes is pending. CI uploads each development binary only after its platform smoke tests pass.
