# Architecture

TeamCode is an independent, self-hosted team gateway. Root package and Gradle group: `me.nathanfallet.teamcode`. All project content is English. The module layout follows Asonar's public contract/client and inward-facing server layers. TeamCode does not depend on ControlResell code, credentials, CI, infrastructure, registries or databases.

## Module graph

```mermaid
flowchart LR
    cli --> client --> api
    app --> presentation
    app --> infrastructure
    app --> domain
    app --> api
    presentation --> api
    presentation --> domain
    infrastructure --> domain
```

| Module | Responsibility | Runtime |
| --- | --- | --- |
| api | Serializable DTOs, typed Ktor resources, public error and event contracts | KMP, JVM and desktop Native |
| client | Typed TeamCode HTTP client, credential supplier and bounded streaming transport; caller supplies the engine | KMP, JVM and desktop Native |
| cli | Native commands, local proxy, browser login, OS credentials, agent integration and service registration | Kotlin/Native; JVM for fast tests |
| domain | Models, use cases, repository/service ports, admission, fairness, capacity forecasts and model selection | Server JVM; no HTTP or database dependency |
| infrastructure | Provider adapters, OAuth, database, encryption, HTTP transports and telemetry | Server JVM |
| presentation | Typed management API, incoming inference protocols, dashboard and agent-facing MCP | Server JVM |
| app | Server composition root, Koin, configuration, lifecycle and OCI image | JVM 21, Ktor Netty |

Infrastructure does not import public DTOs. Presentation maps domain objects to contracts and invokes use cases. CLI never imports server domain or repositories. Native platform code belongs behind `expect/actual` boundaries. The HTTP client accepts a caller-selected engine and credential supplier; the SDK never owns an operating system keychain.

## Deployment

Stateless server replicas and a shared MySQL database. Use HikariCP and reviewed Flyway migrations; schema tests replay migrations against MySQL. No RabbitMQ or Redis requirement initially. Durable directives and quota reservations live in the database. No database lock spans an inference stream.

The local CLI embeds an HTTP loopback-only Ktor CIO server. Outbound HTTPS uses Ktor 3.6 engine defaults (Darwin on macOS, Curl on Linux, WinHttp on Windows); the dependency is shared across native targets. Server CIO and client CIO are different concerns: never assume Native client TLS works merely because the local HTTP server works. The central server uses Netty on JVM.

Native targets: macosArm64, macosX64, linuxX64, linuxArm64, mingwX64. Publish target binaries and checksums; document dynamic library requirements. Compile and test on appropriate hosts, not only JVM. Do not promise static binaries or supported targets before their release artifacts pass verification.

## Request lifecycle

1. Authenticate the TeamCode member; derive identity from the credential, never caller-controlled identity headers.
2. Validate endpoint, model capabilities, project policy, output bound and request size.
3. Atomically reserve the member/group/run allowance including other in-flight reservations.
4. Admit through bounded per-member queues and fair scheduling. Recheck policy at dispatch.
5. Select an authorized provider account with conversation affinity, respecting every relevant quota window and protected sub-pool.
6. Stream with backpressure. Record every provider attempt and its usage; stop upstream work on disconnect.
7. Settle the reservation exactly once. Missing final usage retains a conservative provision for reconciliation.

Do not replay after committed output. Keep provider exhaustion, transient throttling, transport failures and member budget denials separate. Credential refresh is serialized per account. Secrets use versioned atomic updates, so a late refresh cannot overwrite newer credentials.

## Public management contracts

The API owns resources, DTOs and serialization. The client exposes resource subclients and accepts an HTTP engine factory. Version management API under `/api/v1`; inference surfaces retain the upstream-compatible `/v1/messages` and `/v1/responses` paths. Publish capabilities so CLI and server can negotiate compatibility rather than guess feature availability. A planned command must not be advertised as implemented.

## Models and protocols

Separate the caller's requested name, team routing profile, actual provider model and selected account. An alias such as `opus -> astra` is a policy, not a statement that the models share capabilities or protocol. Native passthrough preserves opaque provider fields. Translation is an explicit, tested capability subset: text, tool calls/results, fragmented streaming arguments, stop reasons and errors first. Images and advanced features require their own evidence.

Do not silently discard signed/encrypted reasoning, tool state or unknown required fields. Refuse unsupported capability combinations. Keep routes sticky across tool loops; changing provider requires a compatible session boundary. A paid API fallback or a different data destination requires explicit policy. ChatGPT/Codex subscriptions and OpenAI API credentials are distinct adapters; their tokens are not interchangeable.

## Data and security

Conceptual tables: teams, members, memberships, devices, sessions, service keys, provider accounts, pools, policies, quota observations, reservations, requests, upstream attempts, usage events, route versions, directives, receipts and audit events. Identity and events are team-scoped. IDs, tokens and session names are not metric labels. Telemetry is optional and content-free by default.

Provider credentials are encrypted at rest with the encryption key outside the database. Member/device credentials are revocable and scope-limited. Web sessions use Secure/HttpOnly/SameSite cookies and CSRF protection. Bootstrap is closed by default. There is no public registration. First-run bootstrap creates the administrator once; administrators create other users through the dashboard. Temporary passwords must be replaced before using the gateway.

Metrics describe bounded operational outcomes; individual usage is protected management data. Do not log prompts, responses, credentials or raw provider error bodies. Store only metadata unless an operator explicitly opts into a documented, short-lived diagnostic mode.

### Transaction boundaries

Persistence uses the Exposed DSL behind domain ports, HikariCP for connection pooling and Flyway for MySQL migration execution. All queries, conditional updates and row locks must use typed Exposed expressions, including test fixtures; raw SQL execution is not permitted. Generate new migrations from Exposed table definitions and review their output without changing previously published migration checksums. Database types stay inside infrastructure. Application and fixture queries use this convention, including quota admission, credential refresh and delivery leases. The module-boundary check also rejects raw SQL/JDBC execution in Kotlin sources. Transactions use database time and read-committed isolation, with automatic transaction retries disabled so a failed operation is never silently replayed. SQL statement logging is disabled to protect bound credentials.

Run `python3 scripts/generate-migrations.py` after changing the table definitions. The wrapper starts its own loopback-only MySQL 8.4 container with a temporary memory-backed data directory, invokes the Exposed Gradle migration generator and stops only that container. The generator replays the committed Flyway history and writes candidate DDL; review it before committing, especially renames, drops and MySQL type changes. It never targets the configured TeamCode server database. The schema source lives in `infrastructure/database/tables`; Exposed and migration-generation dependencies stay out of the native CLI.

## Implemented provider and protocol extension points

Three independent contracts keep subscription hosting separate from client wire formats:

- `ProviderTransport` in infrastructure: provider identity, wire protocol, credential renewal, usage probing, quota headers and request dispatch. `ClaudeTransport` and `CodexTransport` implement it. Adding an OpenAI-compatible self-hosted target requires its own configured transport and credential policy; it does not imply reusing Codex subscription authentication.
- `InboundProtocol` in presentation: HTTP path, wire-protocol identity and conversation-envelope validation. `MessagesInboundProtocol` and `ResponsesInboundProtocol` implement it. Authentication, body bounds and streaming response plumbing remain shared.
- `ProtocolAdapter` in infrastructure: request decode/encode plus factories for a fresh `StreamDecoder` and `StreamEncoder` per stream. `MessagesProtocolAdapter` and `ResponsesProtocolAdapter` implement it. `ConversationCodec` registers adapters and rejects duplicate registrations; `StreamTranslation` handles bounded SSE framing and cancellation independently of the format.

```mermaid
flowchart LR
    Client[Claude Code / Codex / future client] --> Inbound[InboundProtocol]
    Inbound --> Gateway[Shared authorization, policy and account routing]
    Gateway --> Transport[ProviderTransport]
    Gateway -. protocol changes .-> Codec[ProtocolAdapter / common conversation]
    Codec --> Transport
    Transport --> Provider[Claude Max / Codex / future provider]
    Provider --> Stream[Native stream or protocol event conversion]
    Stream --> Client
```

A new provider speaking an existing format adds a transport and account verifier, then registers them in composition. A new format adds a wire-protocol identifier, inbound adapter and request/stream adapter, with explicit capability and policy inspection coverage. It is not necessary to add a conversion for every pair: adapters use the common conversation/events only on cross-protocol routes. Native passthrough bypasses these codecs to preserve provider-specific fields. Protocol-specific policy-envelope inspection in the gateway and model/catalog validation still need extending when adding a new format; adding one class alone is not a promise of full compatibility.

See the [TeamClaude comparison](teamclaude-comparison.md) for behavioral differences and remaining acceptance gaps.
