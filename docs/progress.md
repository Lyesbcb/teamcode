# V1 progress

The end-to-end foundation works, including real Claude Max and Codex subscription requests. **V1 release acceptance is not complete.** Successful individual smoke tests do not imply that every client, fallback route or operating-system lifecycle is supported.

| Area | Implemented and verified | Remaining acceptance work |
| --- | --- | --- |
| Architecture and transport | Seven standalone modules, native CLI/client/API separation, authenticated streaming, native macOS/Linux/Windows runtime; Netty pre-response cancellation, inference transport isolation and authenticated shared-DB cancellation control | Inference engine setup/polling overhead and broader backpressure acceptance across advertised architectures |
| Team administration | One-time admin bootstrap, dashboard, admin-created users, password replacement; registered CLI devices, own/admin device and authentication-session management with shared revocation | Groups/additional roles, scoped service credentials, agent-run registry and complete audit coverage |
| Subscription accounts | Claude Max and Codex import/upsert, local OAuth adapters, verified identity, encrypted storage, refresh fencing, shared conversation/account affinity, real inference | Live Codex browser login, full enrollment/reconnect and complex multi-turn agent acceptance |
| Quota protection | Exposed reservations and shared bounded admission queues, member turn rotation, interactive priority/background aging, per-launch native workload selection with shared-service isolation, canonical model-limit bindings and retirement, weighted daily allowances, protected pools, concurrency limits, model/effort restrictions | Real-load scheduling/backpressure acceptance, soft borrowing and reconciliation/operational acceptance |
| Failover and translation | Account retries before configured provider fallback, bounded attempts, no stream replay; real two-turn function-tool tests in both directions and complete real coding tasks in both directions, including controlled mid-conversation fallback, with reasoning disabled | Full-default clients, signed/encrypted reasoning, other tool grammars and real quota/reset-boundary fallback |
| Predictive control | Quota observations including unknown resets/rounded durations, active-target forecasts, persisted recovery hysteresis, session directives/claims, scoped pacing and automatic Claude HTTP/Codex command hooks verified with the real macOS clients | Real-load forecast accuracy, real Codex Windows hooks, subagent fan-out and forecast charts |
| Local CLI lifecycle | Claude/Codex launchers and user services verified on macOS/Linux/Windows | Actual reboot/logon checks, services before login, versioned distribution and safe updates/rollback |
| Deployment | Exposed/HikariCP/MySQL, two-process replica acceptance, Helm, tested multi-architecture Docker publishing | Deployment/recovery operations, restoration evidence, performance measurements and production SLOs |

The immediate sequence is complete agent-compatible routing, then predictive control and the remaining release gates. This sequence does not remove the other V1 requirements. Open-source model providers and centralized MCP and skills management remain V2.

See the [TeamClaude comparison and gap ledger](teamclaude-comparison.md), [detailed evidence](status.md), the [original milestone and acceptance checklist](roadmap.md), and the [Windows setup guide](windows.md). CI failures block new Docker image publication; a newer source commit does not imply that `latest` has been republished.
