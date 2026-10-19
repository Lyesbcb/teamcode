# Session guidance and delivery

TeamCode sends capacity advice at model boundaries so an agent can reduce optional work when the observed quota burn is high, or increase useful parallel work when fresh capacity supports it. Admission independently enforces budgets and concurrency. An advisory message never authorizes more quota or changes an agent's tool permissions.

## Claude integration

```sh
teamcode run claude -- --dangerously-skip-permissions
teamcode run claude --no-guidance -- --dangerously-skip-permissions
```

By default, the launcher creates a temporary plugin under the private configuration directory's `runs/` subdirectory and passes it with `--plugin-dir`. Existing command arguments, plugins and settings remain in place. The plugin contains only an HTTP hook configuration; its authentication references an environment variable and no credential is written into the plugin. The local capability stays scoped to the launched proxy or authenticated user service. The launcher's normal cleanup removes its own plugin directory. An abrupt launcher termination may leave a credential-free directory behind; no cleanup stops other processes.

The hooks run at `UserPromptSubmit` and `PreToolUse`, using Claude's supported [HTTP hook schema](https://code.claude.com/docs/en/hooks#http-hook-fields) and [session-only plugin loading](https://code.claude.com/docs/en/cli-reference). TeamCode requests a four-second hook timeout. Its local handler bounds input at 64 KiB and central lookup at 2.5 seconds, with a separate short receipt deadline. Oversized, unsupported or unavailable guidance returns an empty object. The handler sends no allow/deny decision and does not execute tool arguments.

Only the session ID and delivery stage reach the TeamCode management API. The local proxy discards prompts, tool inputs, working directories and transcript paths from hook payloads. The server hashes the session ID using the same identity as inference affinity, scopes lookup to the authenticated member and requires recent admitted activity. The first request of a new session may precede any advice; the controller must first collect a valid context and quota history.

Explicit `--bare` skips automatic guidance. Claude's managed hook restrictions, HTTP allowlists and disabled hooks remain authoritative and can prevent delivery. The launcher does not override those choices. Failure to prepare the optional plugin reports a short notice and continues with server-side limits.

## Scoped state and receipts

Each client session has its own directive version, expiry and recovery hysteresis. The dashboard/member endpoint retains an aggregate view. Several targets within the same session use their most constrained advice. Sessions belonging to the same member can therefore receive different advice, while still sharing that member's hard quota and concurrency ceiling.

- `POST /api/v1/directives/current` with `sessionId` reads scoped advice; the existing GET reads the member aggregate.
- `POST /api/v1/directives/claim` with `sessionId` and `stage` atomically obtains a 15-second delivery claim for a fresh scoped version.
- `POST /api/v1/directives/receipt` acknowledges the exact directive, session, stage and claim ID. Revoked sessions and replaced/expired claims cannot acknowledge another delivery.

Two replicas or simultaneous hooks get at most one live claim for a version and stage. Once acknowledged, the version is suppressed. If a response or acknowledgment is lost, the claim expires and a later hook may repeat the message. This is retryable emission with deduplication, not exactly-once model processing. `RECEIVED_BY_CLI` and `EMITTED_TO_HOOK` are independent; neither claims agent compliance. All authoritative delivery state is in MySQL.

Delivery rows are bounded to 2,000 per member and 20,000 per deployment, with bounded cleanup after scoped directives expire. At the cap, new claims return no message; inference continues under hard limits. Raw session IDs and tool content are not persisted. The session table is separate from the existing one-row-per-member directive table, preserving old readers during rolling upgrades. New reservation metadata is additive.

## Other integrations and boundaries

```sh
teamcode agents watch --session SESSION_ID
teamcode agents hint --session SESSION_ID --format json
teamcode agents hint --session SESSION_ID --format claude
```

Watch uses the received-by-CLI stage; hint uses emitted-to-hook. Manual bridges must supply the session ID actually attached to inference. Codex already forwards its session header and can consume this API/CLI contract; automatic Codex context injection is still pending.

Claude subagents can share their parent's session ID. They currently share that session's aggregate advice and delivery record; the first matching hook receives a changed version. Independent fan-out to every subagent requires an additional consumer identity and remains explicit work. A receipt does not prove that every subagent received the instruction. Inference without a session header retains member-level limits and advice, but cannot receive automatic scoped messages.

## Verification

`RouteDirectivePersistenceTest` uses actual MySQL and independent connection pools to exercise distinct slow/fast sessions, scoped pacing alongside hard member limits, atomic claims, receipt deduplication, lost-claim recovery, owner isolation and token revocation. HTTP tests cover authentication and claim DTOs; local proxy tests prove that tool input does not leave the workstation and failures do not expose diagnostics.

The normal native launcher smoke inspects the generated plugin, calls the actual native hook endpoint, preserves literal user arguments and verifies cleanup. Windows acceptance includes the Unicode plugin path and loopback hook route in its npm launcher/service scenarios.

For the real Claude client without consuming a subscription:

```sh
python3 scripts/smoke-claude-guidance.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --claude /absolute/path/to/the/actual/claude/executable
```

This isolated fixture supplies deterministic inference and directive responses. It proves real hook loading, correlation between hook/inference session IDs, UserPromptSubmit/PreToolUse context injection, a harmless Bash tool round trip, receipts and plugin cleanup. It does not establish forecast accuracy under real quota consumption or prove model compliance with pacing. See [status](status.md) for completed runs and pending platform CI.
