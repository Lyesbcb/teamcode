# Implementation roadmap

This is the target sequence; see [V1 progress](progress.md) for the current overview and [status](status.md) for actual evidence. Estimates are planning ranges for a developer familiar with Kotlin/KMP, not a deadline or a claim about agent throughput.

| Milestone | Deliverable | Exit evidence | Rough effort |
| --- | --- | --- | --- |
| J0 | Seven modules, contracts, native transport and deterministic local upstream | Native CLI -> typed client -> Ktor; streaming and cancellation demonstrated | 2–4 days |
| J1 | Team login, CLI account add/import/upsert/reconnect, secure server enrollment, basic dashboard, one Max account | Administrator-created login -> CLI -> provider -> visible usage; no provider credentials used by developer proxy | 1–2 weeks |
| J2 | Reliable pool, refresh serialization, all quota windows, bounded failover and session affinity | Exhaustion, concurrent refresh, early/late stream errors and disconnects reproduced | 1–2 weeks |
| J3 | Members/groups/devices, durable reservations, fair scheduling, protected pools, audit | Heavy member cannot starve a protected colleague; restart/revocation cannot reset allowance | 1–2 weeks |
| J4 | Forecasting and predictive pacing, directives and local hooks, forecast UI | Replayed load slows/recovers without oscillation; delivery distinguished from enforcement | 1–2 weeks |
| J5 | Catalog, Responses/Codex and explicit translation | Real multi-tool code task for every advertised route; unsupported capabilities rejected | 2–4 weeks |
| J6 | Native releases, user/system service installers, updates, recovery and operations | Clean installs on target OSes, boot/logon reconnection, restoration and rollback demonstrated | 1–2 weeks |

First PR/commit sequence: document architecture; wire seven modules and enforce graph; deterministic streaming upstream and native proxy; team/device identity; real provider enrollment; provider reliability; team product; controller; multi-model compatibility. Keep slices runnable and push tested milestones.

Tests use synthetic fixtures and local fake providers. Real-provider acceptance is opt-in, capacity-bounded and never a public PR CI dependency. Measure gateway-added latency, first-byte latency, queue wait, success rate, memory per stream, registry throughput and forecast error before choosing production SLOs or quoting hosting costs.

## Release acceptance

- Standalone public checkout; only `cli -> client -> api` on the local side.
- Native HTTPS, streaming, backpressure and cancellation on advertised OS targets.
- Account identity verified server-side; add/import upsert preserves policy/history; source config unchanged; partial imports and retries explicit.
- No credential leakage, unsafe refresh overwrite, unbounded retry or replay after output.
- Atomic quota admission, missing-usage reconciliation, fair interactive access and protected pool isolation.
- Forecasts handle resets, outside spend, stale measurements and unknown capacity without accelerating blindly.
- Directed advice is sparse, expiring and acknowledged honestly; no arbitrary process termination.
- Multi-model tool loops and result correctness, not just text responses.
- Dashboard authorization/CSRF, audit, revocation and route explanations.
- Service install/uninstall idempotence, boot/logon behavior, locked keychain/offline recovery and draining updates.

## V2: open-source model providers

The first open-source model provider is deferred to V2. V1 supports pooled Claude Max and Codex subscriptions, including explicitly supported routes between them. Keep the provider and protocol interfaces extensible; implementing or accepting an OSS backend is not a V1 release gate.

## V2: centrally managed MCP servers and skills

TeamCode will extend team administration to agent tools and reusable skills. This is planned V2 scope, not part of the current implementation or release acceptance.

### MCP gateway and access control

- Administrators register remote MCP servers in the dashboard and assign access to members or groups, with optional per-tool restrictions.
- TeamCode exposes an authenticated MCP gateway. Tool discovery shows only authorized tools, and every tool invocation checks current server-side permissions. Hiding tools in a client configuration is not an authorization boundary.
- Upstream MCP credentials remain encrypted on the server. Support shared team credentials and, where required by the upstream service, individual delegated authorization with explicit ownership and revocation.
- `teamcode run claude` and `teamcode run codex` configure the agent's TeamCode MCP connection using scoped credentials, without requiring manual setup on each workstation or overwriting unrelated personal configuration.
- Add bounded tool execution, concurrency/rate limits, metadata-only audit records and per-member usage visibility. Keep authorization and any required session state compatible with multiple stateless server replicas.
- Acceptance: two members with different assignments see and can invoke only their authorized tools; direct unauthorized calls fail; revocation takes effect server-side; upstream credentials never reach the workstation.

### Skill catalog and synchronization

- Administrators manage a versioned skill catalog in the dashboard, pin approved versions and assign skills to members or groups. Provide update, rollback and removal controls.
- The native CLI synchronizes assigned skills into an isolated, agent-compatible local installation before `teamcode run`. Preserve personal skills and report naming conflicts explicitly.
- Track installation/version status separately from permission to use protected resources. Skills are instructions and files loaded by the agent, not server-executed MCP tools.
- Do not treat downloaded skill files as an access-control mechanism: users can copy instructions they have received. Enforce access to protected tools, credentials and data at the MCP gateway or backing service.
- Acceptance: clean setup and subsequent launches install the assigned pinned versions, updates/rollback are reproducible, removed assignments stop being provisioned, and personal configuration is preserved.

The dashboard, team identity, native launcher and shared persistence provide the integration points. The inference proxy alone does not configure an agent's MCP connections or install its skills; these require explicit gateway and CLI capabilities.


## V2: Kubernetes cloud agent sessions

Explore running agent sessions remotely on a connected Kubernetes cluster. This is V2 scope; the API contracts and interaction model remain to be discussed before implementation.

- Administrators connect a Kubernetes cluster and configure the namespaces, execution templates and resources available to the team.
- An authenticated API creates and manages agent sessions in pods, with automatic provisioning, lifecycle tracking and cleanup.
- Support automatic pause after a configurable inactivity timeout and explicit pause/resume through the API and CLI. Detaching a workstation does not make a session idle while the agent, tools or queued messages are still doing work. Define activity signals and timeout behavior before implementation.
- On pause, stop accepting new work for that execution, drain or checkpoint safely, persist the workspace, transcript and supported agent continuation state, then terminate the pod or complete its Job. Paused sessions retain no running pod; stored data still consumes storage and may incur cost. Track pause progress/failure explicitly so an incomplete checkpoint is not advertised as resumable.
- Resume the same logical session in a newly provisioned pod, restore its workspace and supported conversation state, and issue fresh scoped credentials after rechecking current quotas, permissions, MCP assignments and skills. Do not assume Kubernetes preserves process memory or that every agent supports transparent continuation; verify the supported agent resume mechanism. Messages received while paused must have a defined queue/rejection and wake-up policy.
- Persist lifecycle transitions so concurrent resume requests or multiple server replicas cannot launch duplicate executions. Define retention, expiration and intentional deletion separately from inactivity pause; inactivity must not silently delete resumable work.
- Cloud sessions use the same TeamCode inference proxy, member quotas, routing rules and predictive pacing as local sessions. They receive only their assigned MCP access and pinned skills through the planned V2 management capabilities.
- Users can attach from their workstation through the native CLI to interact with a remote session and reconnect later. The precise terminal/control protocol remains open.
- Provide API access to session transcripts and allow sending messages to a running session. Discuss transcript format, pagination/streaming, message delivery/acknowledgment and concurrent CLI/API interaction before fixing a contract.
- Enforce member/group authorization for session creation, attachment, messages and transcripts. Scope pod credentials to the session, revoke them when it ends and keep cluster credentials on the server. Bound pod resources, concurrent sessions and idle retention.
- Persist desired session state and lifecycle events so server replicas can coordinate provisioning without duplicate pods. Define transcript/workspace retention and cleanup policies separately from pod lifetime.

Acceptance direction: create a session by API, observe its pod and status, attach from the CLI, send a message and retrieve the resulting transcript through the API; demonstrate shared quota/MCP authorization, reconnection, inactivity pause with no running pod, restoration into a new pod without losing supported workspace/history, replica coordination and cleanup. These are proposed V2 acceptance scenarios, not implemented capabilities. Workspace provisioning, repository access, artifacts, scheduling across clusters and interactive session semantics require further product discussion.
