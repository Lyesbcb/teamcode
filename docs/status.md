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
