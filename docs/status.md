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

Still planned, not advertised as working: browser/device login, provider add/import/reconnect, database-backed account storage, real Claude Max pooling, dashboard, team membership, durable quotas, predictive controller/agent integration, model translation, OS service installation and releases for all platforms. Windows private config is fail-closed until its DACL implementation is verified. See [roadmap](roadmap.md).

## Account enrollment groundwork

Implemented as independently tested building blocks, not yet an enabled enrollment API or CLI command:

- Bounded TeamClaude OAuth account parser with per-entry unsupported/invalid results; no proxy keys or source identity hints imported.
- Credential redaction and account+organization identity types.
- A credential merge use case preserving TeamCode identity, custom name, disabled state, pool, priority and handover status; stale versions are rejected.
- AES-256-GCM credential envelopes with random nonces and authenticated account/team context; wrong key/context or malformed ciphertext fails without exposing the input.
- SDK connection/idle timeouts, management deadlines and a regression test keeping upstream response lifetimes open until downstream streaming completes.

The database adapter, server-side provider identity verification and add/import/reconnect commands must land before this becomes actual account enrollment. In-memory records are not presented as durable team state.
