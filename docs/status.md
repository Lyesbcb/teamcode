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

The subsequent Windows execution passed native configure, login, required password replacement, atomic config replacement and status using a Unicode path. Its ACL inspection fixture then exposed inherited PowerShell 7 module paths breaking Windows PowerShell 5.1 auto-loading. Both the fixture and the CLI's built-in service query now select Windows PowerShell's own system modules. Full Windows acceptance is still pending; macOS service regression passed after the Okio update.

The next Windows execution passed those checks, actual owner-only ACL inspection and rejection of an untrusted HTTPS certificate. The trusted-HTTPS fixture then timed out on the Windows current-user root-certificate confirmation dialog. The full test now requires an explicit disposable-machine certificate flag and uses the elevated CI runner's machine store, removing its generated certificate afterward. This affects the acceptance fixture only; TeamCode does not install certificates. The TeamClaude Windows comparison and necessary native adaptations are documented in [the workstation guide](windows.md#teamclaude-reference-and-native-adaptations).

### Windows launcher acceptance and Task Scheduler encoding

- Windows CI run `39177066176` passed the native login/password-change, private ACL, Unicode configuration/binary path, HTTPS trust/rejection, progressive SSE, Claude/Codex npm launcher, literal arguments/stdin/stdout, exit-code and descendant-cleanup checks. The first reached service installation failed while Task Scheduler parsed its XML encoding declaration.
- Windows registrations now use UTF-16LE with a BOM and a matching declaration; registration ownership compares the actual encoded bytes. The JVM manifest test parses those exact bytes, including the Unicode configuration path. Local CLI tests and MinGW linking passed; Task Scheduler runtime acceptance is still pending the next Windows run.

### TeamClaude parity review and protocol extension points

- Added `docs/teamclaude-comparison.md`, pinned to the reference checkout, with corrected differences, intentional shared-server adaptations and remaining acceptance gaps. Incomplete quota readings, model aliases, affinity, forecast relevance and full real-agent compatibility remain explicit open work.
- Corrected named Codex quota headers, shared named session windows, utilization above 100%, Claude status-only exhaustion, per-model fallback exclusions and temporary 403 cooldowns. A model-only rejection can now fall back to another model on the same subscription without cooling the whole account.
- Added bounded forced renewal after a rejected unexpired access token. Two independent SQL-backed credential sources renew the same rejected token once; a delayed rejection reuses the newer saved token. Existing pending-refresh/crash fencing remains enforced. A rejected first attempt does not refund an ambiguous second attempt.
- Split HTTP ingress, request codecs and SSE decoders/encoders into protocol adapters. Provider transports remain independent. Native passthrough skips translation; existing cross-protocol stream and tool tests pass after the split.
- Local verification: `:domain:test :infrastructure:test :presentation:test :cli:jvmTest :app:buildFatJar` passed, including actual MySQL replica tests: 11 domain, 35 infrastructure, 7 presentation and 14 CLI tests, zero failures/skips. No real provider calls were made for this review. Windows service acceptance remains a separate live CI gate.

### Direct Codex OAuth enrollment implementation

- `teamcode accounts add --provider codex` now uses Codex's public client, fixed localhost callback URI, PKCE and state validation. The user pastes the complete callback URL into a hidden prompt, including when the browser cannot load that local page. No callback listener or browser process is started. Claude retains its `code#state` flow (`--provider claude`, default; `claude-max` is also accepted).
- `OAuthEnrollmentProvider` has separate Claude and Codex implementations; `OAuthEnrollmentClient` shares bounded token exchange and response validation without forwarding TeamCode credentials or following redirects. Both send the resulting credentials through the existing server-verified upsert.
- Four new client tests cover authorization parameters, missing/mismatched/duplicate state, callback origin, provider errors, code exchange, incomplete credentials, body bounds and redirect refusal. Client/CLI JVM tests, macOS ARM64 and Windows x64 native linking passed. The rebuilt macOS binary passed the native launcher smoke against the rebuilt server (literal arguments/stdin, isolated capabilities, simultaneous proxies, exit status and cleanup).
- Live Codex browser consent/enrollment has not been performed; it remains an acceptance step requiring the account owner's interactive login. Existing real Codex import/inference evidence is separate from this new login path.

- Windows CI `39177857445` reached and passed actual Task Scheduler installation, authenticated health, idempotent install and service status. It then failed because the test still decoded the new UTF-16 registration as UTF-8; the fixture now reads the declared encoding. Native macOS/Linux, JVM/MySQL and Helm jobs passed in that run. Remaining Windows drain/reuse/restart checks require the next run.


### Windows native lifecycle accepted

CI run `39178163269`, commit `45b43fd`, passed all Windows native acceptance stages on the actual Windows runner:

- Login, required password change, configuration persistence, private NTFS ACLs, Unicode paths and HTTPS trust/rejection.
- Progressive authenticated SSE, Claude/Codex npm launchers, literal arguments/stdin, inherited stdout/stderr, exit codes, normal descendant cleanup and abrupt launcher termination.
- Task Scheduler installation/idempotence, authenticated service discovery and reuse, graceful drain during a live stream, start/restart, uninstall and logout.

The tested `teamcode-windows-x64` development artifact was uploaded after acceptance. This is runtime evidence, not just a successful cross-compilation. Actual workstation reboot/logon, system services before login and versioned distribution remain open V1 acceptance work.

The entire run completed successfully: JVM/MySQL, Helm, macOS native, Linux native, Windows native and Docker publication. Docker Hub inspection confirmed that `latest` and `sha-45b43fd59eea4451dee5fea6e95a3f8b3e8f0c30` resolve to the same OCI index, `sha256:818221e05ebcdb04f9df00bf98ebe6b551ac84966bd5d87bb82308a5e5205cb6`, with `linux/amd64` and `linux/arm64` images. The Helm default remains `nathanfallet/teamcode:latest`.

See [V1 review](v1-review.md) for the product walkthrough and the remaining release gates; this successful build does not close the full V1 roadmap.


### Durable conversation affinity across replicas

- Added bounded MySQL conversation hints, scoped by authenticated team/member and requested model, with one-hour expiry and two-minute activity scoring. Placement and admission share the team-locked transaction; all quota, pool, cooldown, priority and concurrency checks still apply.
- Claude-specific and Codex generic session headers now survive the native proxy/SDK/server path through a shared allowlist. Malformed or duplicate identifiers disable the hint. Opening-message hashes distinguish subagents without persisting raw session IDs or prompts.
- Remembered cross-provider targets remain stable across turns and are invalidated by route changes. Unsupported conversion skips to a configured compatible target; all-incompatible requests still fail explicitly before provider dispatch.
- Local evidence (2026-10-19): `:api:jvmTest :domain:test :infrastructure:test :presentation:test :client:jvmTest :cli:jvmTest :app:buildFatJar` passed. Actual MySQL tests used two independent connection pools and covered model-specific diversion, competing reservations, eligibility changes, member/pool isolation, expiry, revocation and the 1,000-hint member cap. Module boundaries and `git diff --check` passed.
- The rebuilt macOS ARM64 native binary passed `scripts/smoke-native.py` against the rebuilt server: private configuration, authenticated SDK/proxy transport and progressive SSE. No real provider calls were made for this milestone. Cross-platform CI for this change remains pending publication.

See [conversation affinity](session-affinity.md) for behavior, bounds and remaining live-agent/reset-boundary acceptance. This milestone does not close the remaining [V1 review gates](v1-review.md).


Conversation-affinity CI acceptance: run `39179684615` for commit `d96aee1` completed successfully across JVM/MySQL, Helm and actual native macOS/Linux/Windows tests, followed by successful Docker publication. Registry inspection verified the commit image as OCI index `sha256:c2fb9e389a5af7158fe16959060e37fd00e61684d311cfcf4a4e7fcedb0de362`, containing `linux/amd64` and `linux/arm64`. The subsequent forecast changes have separate local/CI acceptance and are not covered by that run.


### Forecast scope and durable recovery

- Advice now uses recently admitted provider/model targets, their original protocol and current route fingerprint. Requested/resolved model policy, effort and explicit thinking budgets are rechecked. Missing/inactive/invalid contexts yield UNKNOWN, rather than borrowing headroom from idle providers or unrelated model windows.
- Only accessible pool accounts in the selectable priority tier contribute. Proven 95% exhaustion allows the same lower-priority eligibility as admission. Advice respects physical account ceilings and the identical weighted member/pool allowance calculation used for reservations; zero share or exhausted budget produces an allowance-specific PAUSE.
- Tightening and unknown data apply immediately. Recovery must remain stable for two minutes; the pending candidate/timestamp persist in MySQL, and route/policy/pool/eligibility changes invalidate the previous context. Fresh observed exhaustion no longer requires a learned rate, and extremely small rates cannot overflow the forecast timestamp.
- Local evidence (2026-10-19): `:domain:test :infrastructure:test :presentation:test :app:buildFatJar` passed: 14 domain, 42 infrastructure and 7 presentation tests, zero failures/errors/skips. MySQL tests use independent connection pools and cover route/provider/model scope, effort changes, pool isolation, missing/stale observations, weighted allowances, recovery continuity and context invalidation. Existing account-affinity and replica regressions pass with the additive V8 migration.
- The actual macOS ARM64 CLI passed `scripts/smoke-native.py` against the rebuilt packaged server (private configuration, authenticated proxy/SDK, progressive SSE). Module boundaries and diff whitespace checks passed. Tests used synthetic data and no real provider requests. Cross-platform CI for this milestone is pending the new push.

Advice is still delivered per member, conservatively taking the most constrained active target. Sparse per-session delivery, automatic hook integration, outside-spend/reset/bursty real-load replay, observation-only rollout and fair scheduling remain V1 work; this milestone does not claim optimal orchestration or finish the full roadmap.


Forecast-scope CI acceptance: run `39180421393` for commit `6bee322` completed successfully across JVM/MySQL, Helm, native macOS/Linux/Windows and Docker publication. This run predates the session-delivery changes below.

### Scoped delivery and automatic Claude hooks

- Added separate session directives and durable 15-second emission claims/receipts. Independent replicas suppress acknowledged versions, recover lost claims and reject revoked, foreign, expired or replaced claims. The original member table retains its one-row primary-key contract for rolling upgrades; session metadata on reservations is additive.
- Admission applies pacing within the current session while retaining hard member, pool and account limits. A new identified session does not inherit another session's forecast throttle. Headerless callers retain the member aggregate. Current scoped advice requires recent activity; raw session IDs are hashed before persistence.
- `teamcode run claude` now installs a temporary invocation-only HTTP-hook plugin. Local processing is bounded and forwards only the session ID/delivery stage, discarding tool content and transcript paths. Changed advice is emitted as additional context at UserPromptSubmit/PreToolUse; failures emit empty context. Personal settings remain unchanged. `--no-guidance` and explicit `--bare` skip automatic installation.
- Added `agents watch --session` and required `--session` for manual `agents hint`, with separate received/emitted stages. The loopback proxy uses the existing local capability; plugin files contain an environment-variable reference, never a credential. Normal launch completion removes its own plugin, and optional-plugin failures preserve the child execution/exit behavior.
- Local suite evidence (2026-10-19): API (1), domain (14), infrastructure (42), presentation (8), client (4) and CLI (16) tests passed, zero failures/errors/skips. The final MySQL targeted regression also passed after adding owner isolation, hard member concurrency and rolling-upgrade assertions. Packaged server, macOS ARM64 native and MinGW native linking passed; module boundaries, Python fixture syntax and diff whitespace checks passed.
- The rebuilt macOS native launcher passed Claude/Codex literal arguments, stdout/stdin/cwd, separate concurrent proxies, local HTTP hook configuration/calls, opt-out/bare behavior, exit/signal status and plugin cleanup. Windows native acceptance now includes the generated Unicode plugin path and HTTP hook in its existing npm/service fixture; execution of that updated fixture awaits the new CI run.
- `scripts/smoke-claude-guidance.py` passed with the actual installed Claude Code **2.1.302** and native TeamCode. The isolated deterministic server proved both UserPromptSubmit and PreToolUse context injection, correlation of real hook/inference session IDs, an actual harmless Bash tool round trip, emission receipts and temporary-plugin cleanup. It used no provider credentials or subscription calls. This proves client integration, not forecast accuracy or model obedience under real load.

See [agent guidance](agent-guidance.md) for the retry/receipt contract, defaults and limits. Automatic Codex injection, independent subagent fan-out, forecast replay/observation rollout and the other [V1 review gates](v1-review.md) remain open. Cross-platform CI for this milestone is pending the push; do not infer its acceptance from the previous green run.


## Native Codex guidance (2026-10-19)

The preceding session-guidance commit `f1327d8` completed [CI 39181558080](https://github.com/nathanfallet/teamcode/actions/runs/39181558080) successfully: JVM, macOS, Linux, Windows, Helm and multi-architecture Docker publication all passed. This includes the previously pending Claude Windows plugin/HTTP-hook fixture.

This milestone implements `teamcode agents hook` and automatic command hooks in `teamcode run codex`, sharing parsing, scoped claim lookup and receipt handling with Claude. Hook definitions contain only stable executable/config paths; credentials remain in private configuration. Input and network deadlines are bounded. Codex trust review remains mandatory; the launcher does not bypass it or alter existing configuration files. Explicit CLI hook overrides are preserved with a notice and a documented manual bridge.

Local evidence:

- `:cli:jvmTest`, macOS native linking and MinGW native linking passed. Production module boundaries and whitespace checks passed.
- `scripts/smoke-hook.py` passed on the actual macOS binary: both supported events, exact 64-KiB boundary, malformed/invalid UTF-8 input, held-open stdin deadline, network deadline, expiry, receipts/deduplication, private payload isolation and Unicode configuration path.
- `scripts/smoke-run.py` passed with the actual native launcher, including execution of generated Codex hook commands, literal arguments, opt-out, concurrent proxies and cleanup.
- `scripts/smoke-codex-guidance.py` passed with actual **codex-cli 0.163.0** and native TeamCode. Five isolated scenarios cover both context boundaries, an actual harmless `exec_command` round trip, correlation of hook/inference session IDs, receipts, preservation of user hooks, refusal to run untrusted hooks, opt-out, leaf-scope overrides with `--ignore-user-config`, and preservation of custom CLI hooks. Executable/configuration paths include spaces, Unicode, an apostrophe and a dollar sign. No real provider requests or user configuration were used.
- The shared bridge refactor also passed the real **Claude Code 2.1.302** guidance fixture with its Bash tool round trip.

Native stdin and generated-command checks are wired into desktop CI, including Windows PowerShell execution. Their updated cross-platform run is pending this push; MinGW linking alone is not Windows runtime acceptance. Full real Codex Windows client acceptance, independent subagent fan-out, forecast load replay/charts, and the remaining [V1 gates](v1-review.md) stay open.


### Windows hook executable lookup

CI runs [39182797785](https://github.com/nathanfallet/teamcode/actions/runs/39182797785) and [39217843645](https://github.com/nathanfallet/teamcode/actions/runs/39217843645) exposed a Windows hook invocation failure. The Windows acceptance deliberately removes nonessential PATH entries; Windows PowerShell lives below `System32/WindowsPowerShell/v1.0`, outside that minimal PATH. Native/npm launches worked but the new guidance command could not find PowerShell. Docker publication remained blocked by the failing Windows gate.

The launcher now adds the OS-provided PowerShell directory to the Codex child environment only. The minimal-PATH acceptance remains intact. Local CLI JVM tests, macOS/MinGW linking and the real Codex 0.163.0 five-scenario guidance fixture pass. Windows runtime confirmation awaits the fix's CI run; linking is not a substitute for it.


## Unknown reset observations and rounded windows (2026-10-19)

Provider usage and response headers now retain a valid percentage when no reset epoch is reported. `AccountQuota`, the public quota DTO, both SQL quota tables and dashboard rendering distinguish an unknown date from zero usage. Missing/invalid percentages still create no observation; known expired windows are not revived. Claude numeric/ISO reset and percentage aliases are covered. Codex usage/header durations use TeamClaude's shared 10% tolerance around five hours/seven days, rejecting unrelated hourly/monthly windows and preserving named weekly scope.

Admission excludes an unknown-reset saturated window only while its observation is at most five minutes old; repeated fresh saturation extends protection. Known future resets retain the previous exclusion behavior. A newer unknown-reset zero observation can clear an old saturation; delayed observations cannot overwrite it. Forecasting never infers an idle/reset-first trajectory from an unknown epoch, while a fresh exhausted window remains actionable even if another window is absent.

Evidence:

- **93 tests passed**: API 2, domain 16, infrastructure 47, presentation 8, client 4, CLI 16. This includes parser aliases/duration boundaries/absent versus zero readings, unknown-reset forecasting and freshness, and API null/known-date compatibility.
- `UnknownQuotaResetPersistenceTest` upgrades an actual MySQL V9 database with an existing quota to V10, preserves the known row, and uses two independent connection pools to prove newer/late observation ordering, scoped saturation, freshness expiry, known-reset protection and future-sample rejection.
- The packaged two-process replica smoke passed: concurrent startup/migration, one admin bootstrap, shared members/sessions, cross-replica revocation, killed-pod recovery and database readiness/recovery. It uses only its own test database and processes.
- Server packaging, native macOS linking and the production module graph check passed. No provider credentials or subscription calls were used.

Deploy all application replicas and update quota API consumers before relying on nullable-reset protection; old readers did not understand it. Active-limit/model-family mappings, removed model caps, real-load forecast accuracy, subagent fan-out and the other full V1 gates remain open. Updated CI/Docker publication for this quota milestone is still pending.

## Exposed persistence and canonical Codex quota scopes (2026-10-19)

The previous unknown-reset milestone (`6b54733`) completed CI run `39219656827`, including every platform and Docker publication. The preceding Windows hook PATH fix also completed all jobs in run `39218729009`; this verifies the native bridge and launcher fixture, not a full real Codex session on Windows.

This slice replaces handwritten JDBC queries throughout the application and test fixtures with Exposed 1.5.0, matching the standalone asonar/pkg conventions. The 23 typed tables retain the existing database shape. HikariCP still owns the bounded connection pool, Flyway still applies migration resources, and database-clock leases/read-committed transactions retain replica coordination. Exposed SQL logging is disabled and automatic transaction retries are disabled. The module-boundary check now rejects raw SQL strings and direct JDBC execution in Kotlin sources. Previously published migrations remain unchanged.

Codex response headers now bind the resolved model to the serving account's canonical active-limit slug. Usage probes use `metered_feature` rather than display labels, and admission, forecasts and rejection classification share those bindings. A complete probe can retire model caps; partial/malformed data cannot. Snapshot timestamps start before the network request, and shared watermarks prevent delayed responses from restoring retired caps. Historical samples remain stored, with separate window-incarnation timestamps preventing a new cap from extrapolating across its prior retirement. Bindings are limited to 32 models per account and never applied to another subscription or to Claude accounts.

Local verification passed:

- **99 tests**, zero failures/errors/skips: API 2, client 4, domain 17, infrastructure 52, presentation 8 and CLI 16. The infrastructure suite includes real MySQL independent-pool admission races, refresh fencing, directive claims, alias isolation, stale snapshot ordering and cap retirement.
- The packaged server passed the two-process replica smoke: concurrent startup/migration, one administrator bootstrap, shared members/sessions, cross-replica revocation, killed-pod recovery and database readiness/recovery.
- Exposed generated exactly three additive migrations for quota snapshots, canonical model bindings and window-incarnation timestamps. Replaying all 13 migrations and generating again produced **zero migrations**, confirming no schema drift. Published V1–V10 resources remain unchanged.
- Server packaging, module boundaries, the raw-query convention check and diff whitespace checks passed. No provider credentials or subscription calls were used.

Cross-platform CI and Docker publication for this slice are pending its push; the previous green runs do not validate these changes.

The first open-source model adapter is now explicitly V2 at the owner's request. Versioned CLI updates, signed release manifests, cooperative service updates and rollback are documented design targets in `services.md`, not implemented commands. The remaining V1 gates in `v1-review.md` are still required.


## Claude model-cap retirement (2026-10-19)

Claude usage probes now carry an authoritative model-cap snapshot only when the payload contains a valid explicit `limits` enumeration. Valid legacy `seven_day_<family>` readings retain precedence, and listed zero usage remains an observation with an optional reset. A null legacy slot, missing enumeration, malformed entry or failed request cannot prove removal. Model-family identifiers are normalized and bounded before persistence.

This follows the sibling TeamClaude `src/oauth.js`, `test/quota-probe.test.js` and `test/scoped-weekly.test.js` behavior. TeamCode additionally validates completeness before retirement and shares snapshot ordering across replicas through Exposed transactions. The probe timestamp precedes the network request. A delayed or equal-time response cannot resurrect a retired model window, and a newer inference observation survives an older probe. Retirement is account-local, never clears a shared subscription window, preserves history and starts a new history interval when a cap reappears. Codex active-limit bindings remain Codex-only. No schema migration is required beyond the preceding Exposed milestone.

Evidence:

- **57 infrastructure tests passed**, zero failures/errors/skips, plus server packaging, module/query conventions and whitespace checks.
- `ClaudeQuotaSnapshotTest` covers full/partial/malformed enumerations, null versus zero, legacy precedence, unknown families, expired windows, request timing and unsuccessful probes using the actual transport with a synthetic HTTP engine.
- `ClaudeQuotaRetirementPersistenceTest` uses real MySQL and two independent pools to verify admission, account isolation, shared exhaustion, retained history, equal/late responses and concurrent header/probe ordering. The existing Codex binding and retirement tests remain green.
- `RouteDirectivePersistenceTest` now also proves that retiring a Claude cap releases forecast pressure after persisted recovery hysteresis; retained history does not recreate the pause.

No provider credentials or subscription calls were used. Cross-platform CI and Docker publication await this push. More subscription-plan shapes, real reset-boundary behavior, complete agent tasks and the other V1 acceptance gates remain open.


## Real Claude Code task through Codex (2026-10-19)

The preceding Claude quota-retirement commit `e2b0f29` completed [CI 39226100498](https://github.com/nathanfallet/teamcode/actions/runs/39226100498) successfully, including JVM, native macOS/Linux/Windows, Helm and multi-architecture Docker publication. This also validates the Exposed conversion; its earlier standalone run was superseded before the macOS/image jobs completed.

The real coding-task fixture found and fixed two conversion boundaries beyond the existing text/function smoke: Claude Code explicitly sends neutral temperature 1, and a tool execution error must remain actionable after translation. Neutral temperature/top-p defaults now use the Codex endpoint's defaults, while custom values remain rejected. Failed Messages tools become explicitly marked Responses tool outputs, retaining their original content and call IDs within the tool-result role. Claude's non-strict tool default is now explicit on Responses requests, so optional parameters are not implicitly normalized into required fields. Explicit strict mode is retained.

Evidence:

- **107 tests passed**, zero failures/errors/skips: API 2, client 4, domain 17, infrastructure 60, presentation 8 and CLI 16. New codec regressions cover default/custom sampling, strictness and optional arguments, failed tool results, IDs and original text/image preservation. Server packaging, module/query conventions, Python syntax and whitespace checks passed.
- The actual **Claude Code 2.1.302** ran through the macOS ARM64 native `teamcode run claude`, a packaged server and a forced route from `claude-haiku-4-5-20251001` to the real Codex subscription model **`gpt-6-luna`**. It used Read/Edit/Bash, corrected a broken Python function on disk and produced a successful final response. Four independently rerun tests passed, and the test file remained byte-for-byte unchanged.
- All **eight inference requests** used the intended Codex target and settled known usage. Six authorized accounts were imported in access-only mode; the source configuration hash remained unchanged. No refresh ran, and the fixture removed its own processes and disposable database. No raw prompts, agent output or subscription credentials were logged.
- The harness uses isolated agent configuration, restricted task tools, an eight-turn/180-second bound, a real native launcher and actual file/test assertions. It is opt-in and excluded from CI.

This closes one real coding-task case, not the entire portability gate: reasoning is explicitly disabled, tools are the selected built-in Read/Edit/Bash set, and the destination is forced from the first turn. Mid-conversation fallback, other reasoning modes and reverse coding-task acceptance remain open. A synthetic capture of installed Codex **0.163.0**, without any provider request, identified the next concrete reverse-route gap: `additional_tools` input items, namespaced/custom tools, reasoning context and verbosity controls. They must be mapped before claiming default Codex-to-Claude coding support. All other V1 release gates remain in scope. CI/Docker publication for this slice awaits its push.


## Real Codex task through Claude and custom tool translation (2026-10-19)

The preceding Claude-to-Codex commit `82a5fe4` completed [CI 39228202859](https://github.com/nathanfallet/teamcode/actions/runs/39228202859) successfully across JVM, native macOS/Linux/Windows, Helm and multi-architecture Docker publication.

Responses developer `additional_tools`, one-level namespaces and supported custom text tools now cross the Claude route. Deterministic aliases preserve tool identity without replica-local state. Claude receives custom text through a strict single-string wrapper; the response adapter validates the complete wrapper, grammar length and Unicode before restoring raw custom text, namespace and call ID. Conflicting declarations, unsupported grammars, invalid named choices and unadvertised tool calls fail explicitly. Ordinary function streaming and native protocol passthrough remain supported.

The supported grammar boundary is free text, two unrestricted regex forms, and the exact nonempty-text Codex code-mode Lark grammar. Arbitrary grammar conversion is not implemented. Verbosity becomes an explicit response-length instruction. Reasoning context `all_turns` is accepted only when reasoning is explicitly disabled. Empty/null `logprobs` and annotations can be replayed; nonempty metadata is not silently dropped.

Evidence:

- **114 tests passed**, zero failures/errors/skips: API 2, client 4, domain 17, infrastructure 67, presentation 8 and CLI 16. Tests cover deterministic identities, collisions, namespaces, custom input and result history, named tool choices, supported/unsupported grammars, Unicode chunking, malformed wrappers and empty output metadata. A gateway regression performs two full turns through the translated transport and verifies raw custom input, replayed IDs and both usage settlements.
- The actual **Codex 0.163.0** ran through the macOS ARM64 native `teamcode run codex`, a packaged server and a forced route from `gpt-6-luna` to the real Claude Max model **`claude-haiku-4-5-20251001`**. It corrected the Python source, executed the tests and completed its turn. The test file remained unchanged and all four independently rerun tests passed. Its normal namespaced/code-mode tools were retained; reasoning was explicitly disabled.
- All **sixteen inference requests** used the intended Claude target and settled known usage. Six verified accounts were imported in access-only mode; the source configuration hash stayed unchanged. No refresh ran, and the fixture cleaned up its own processes and disposable database. No raw prompts, agent output or credentials were logged. Earlier reverse attempts rejected replayed empty `logprobs` before dispatch; the accepted empty-metadata mapping fixes that boundary.
- The first full run exposed an intermittent missing forecast in `RouteDirectivePersistenceTest`; the unchanged targeted case passed. Its synthetic quota snapshot now uses the database clock, matching its sample timestamps and the future-observation guard, instead of mixing host/VM clocks. The fixture asserts every shared-window sample was persisted before evaluating guidance. The subsequent complete suite passed; production forecast behavior was not relaxed.
- Packaged server, module/query conventions, Python syntax and diff whitespace checks passed. No schema, CLI binary implementation or dependency change is required.

Both directions now have a complete real coding-task case with reasoning disabled and the destination forced from the first turn. This does not close full-default reasoning, arbitrary tool grammars, live mid-conversation fallback, real quota/reset-boundary acceptance or the other V1 gates. Cross-platform CI and Docker publication for this slice await its push.


## Real coding tasks across a mid-conversation fallback (2026-10-19)

The preceding custom-tool bridge commit `b5cf352` completed [CI 39231303645](https://github.com/nathanfallet/teamcode/actions/runs/39231303645) successfully: JVM, Helm, native macOS/Linux/Windows and multi-architecture Docker publication.

The opt-in coding fixture now supports `--coding-fallback`. Both agents start on their native provider with the other provider configured second. A loopback HTTP fixture recognizes a matching call/result in the real agent's history and disables native-provider accounts through the disposable server's management API before forwarding the original request bytes. The route version and targets must remain unchanged. Provider responses are streamed unchanged; the fixture does not manufacture inference output. Its private configuration, account settings, listener and processes are cleaned up on exit.

Evidence:

- **Claude Code 2.1.302:** one native `claude-haiku-4-5-20251001` request, followed by **nine Codex `gpt-6-luna` requests** after the tool boundary. Actual Read/Edit/Bash execution corrected the source, left the test file unchanged and passed four independent tests. The final agent result succeeded.
- **Codex 0.163.0:** one native `gpt-6-luna` request, followed by **eleven Claude `claude-haiku-4-5-20251001` requests**. Its namespaced/code-mode tool conversation continued through the switch, corrected the source and completed the turn. Four independent tests passed and the test file was unchanged.
- All **22 client inference responses were HTTP 200** with the expected target, correlated by response request ID to dispatch and successful terminal usage. There were no unknown-usage settlements, translation failures or hidden nonzero unmatched attempts. The evidence verifier was rerun against both completed metadata logs after its final extraction.
- **Four provider-free fixture tests pass:** matching tool-history detection; authenticated HTTP forwarding and exact body preservation; one-time capacity removal and restoration on failure; and rejection of incorrect targets, 429s, missing correlations, failed streams and charged unmatched attempts. These tests now run in JVM CI before Gradle. Real subscription tests remain opt-in and excluded from CI.
- Each run imported six verified accounts in access-only mode, did not refresh provider credentials, preserved the source configuration hash and removed its own disposable resources. No provider tokens, prompts or raw agent output were logged. Only fixture/server metadata is reported.

An early Claude run failed within an eight-turn budget after tool errors; a later task completed but failed the old count-only request assertion. The final fixture allows twelve Claude turns within the existing 180-second deadline and correlates individual response IDs instead of assuming every provider attempt equals one client request. Any unmatched attempt must have settled with zero charge; any client error still fails acceptance. The final successful runs required ten and twelve requests respectively. No production Kotlin code, schema, dependency or native launcher changed in this slice; its existing 114-test JVM baseline is additionally covered by the preceding green CI.

This validates controlled mid-conversation fallback with reasoning disabled. It does not simulate actual quota exhaustion, reset timing, signed/encrypted reasoning transfer or every client default. Those gates, fairness/reconciliation, the remaining team features, distribution and operational acceptance stay in scope. CI publication for this fixture/documentation slice awaits its push.
