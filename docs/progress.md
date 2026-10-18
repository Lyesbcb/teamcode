# V1 progress

The end-to-end foundation works, including real Claude Max and Codex subscription requests. **V1 release acceptance is not complete.** Successful individual smoke tests do not imply that every client, fallback route or operating-system lifecycle is supported.

| Area | Implemented and verified | Remaining acceptance work |
| --- | --- | --- |
| Architecture and transport | Seven standalone modules, native CLI/client/API separation, authenticated streaming, native macOS/Linux runtime | Complete Windows runtime acceptance; broader cancellation/backpressure acceptance across advertised targets |
| Team administration | One-time admin bootstrap, dashboard, admin-created users, password replacement, session revocation | Groups, devices and scoped service credentials; complete audit coverage |
| Subscription accounts | Claude Max and Codex import/upsert, verified identity, encrypted storage, refresh fencing, real inference | Direct Codex login, full enrollment/reconnect acceptance and session/account affinity |
| Quota protection | SQL reservations, weighted daily allowances, protected pools, concurrency limits, model/effort restrictions | Fair interactive scheduling, soft borrowing and reconciliation/operational acceptance |
| Failover and translation | Account retries before configured provider fallback, bounded attempts, no stream replay; real two-turn function-tool tests in both directions | Full real-agent coding tasks, signed/encrypted reasoning and provider-specific tools; first open-source model adapter |
| Predictive control | Quota observations, forecast/controller logic, directives, admission pacing, CLI guidance hooks | Replay accuracy and stability, sparse per-session delivery, real-agent integration and forecast charts |
| Local CLI lifecycle | Claude/Codex launchers and user services verified on macOS/Linux | Finish Windows acceptance, actual reboot/logon checks, services before login, versioned distribution and safe updates/rollback |
| Deployment | HikariCP/MySQL, two-process replica acceptance, Helm, tested multi-architecture Docker publishing | Deployment/recovery operations, restoration evidence, performance measurements and production SLOs |

The immediate sequence is Windows acceptance, complete agent-compatible routing, then predictive control and the remaining release gates. This sequence does not remove the other V1 requirements. Centralized MCP and skills management remains V2.

See [detailed evidence](status.md), the [original milestone and acceptance checklist](roadmap.md), and the [Windows setup guide](windows.md). CI failures block new Docker image publication; a newer source commit does not imply that `latest` has been republished.
