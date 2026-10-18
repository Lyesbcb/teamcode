# TeamCode contributor instructions

- All repository content must be English. Root package and Gradle group: `me.nathanfallet.teamcode`.
- Standalone public project. No ControlResell dependencies, registries, secrets, infrastructure or CI.
- Keep `cli -> client -> api`. The CLI must never depend on server domain or infrastructure modules.
- Server layers: `app -> presentation + infrastructure + domain + api`; `presentation -> domain + api`; `infrastructure -> domain`. Domain and API do not depend on other project modules.
- Put business rules in use cases and services, never in HTTP routes or CLI commands. Use constructor injection and Koin in the server composition root.
- Native CLI: no JVM at runtime. Test the actual native binary; JVM tests alone do not validate TLS, streaming or process behavior.
- Never log credentials, prompts or response bodies. The only exception is the initial administrator temporary password, printed once after first-run creation commits, as explicitly requested by the project owner. Never print it again on restart. No real provider calls in normal tests or PR CI.
- Preserve coroutine cancellation. Bound request bodies, queues, retries and network waits. Never replay a stream after committed output.
- Update `docs/status.md` with evidence when a milestone changes. Never present a planned command as implemented.
- Never stop processes or Gradle daemons started by someone else. Never kill by process name.
