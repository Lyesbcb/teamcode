# V1 review checklist

This is a review of the working foundation, not a declaration that V1 release acceptance is complete. Start with [current progress](progress.md), [verified evidence](status.md) and the [TeamClaude gap ledger](teamclaude-comparison.md).

## Walk through the implemented product

1. Deploy the chart against a test MySQL database and keep its encryption key across restarts. Confirm the image commit associated with the successful build; `latest` advances only after every required CI job passes.
2. Use the one-time bootstrap administrator password, change it, create a member in the dashboard and sign in as that member. Confirm there is no public signup and administrative pages are unavailable to the member.
3. Set the self-hosted URL with `teamcode login --server https://your-host`. Restart the CLI and check that authentication and the URL persist. Confirm password replacement and session revocation.
4. As the administrator, inspect `teamcode accounts import --from teamclaude --dry-run`. For a temporary trial while TeamClaude remains the refresh owner, import with `--access-only`. Verify repeated import updates the same identities and preserves account settings. A permanent switch uses explicit refresh handover after the old refresher is stopped.
5. Exercise `teamcode accounts add --provider claude` and `--provider codex`. Codex's direct browser consent is the outstanding live enrollment check: paste the complete callback URL into the hidden prompt. Check reconnect/upsert and explicit ownership after enrollment.
6. Set member weights, concurrency and pool budgets in the dashboard. Set allowed models/efforts, including the decision about Fable. Confirm a rejected policy is visible to the caller and does not dispatch a provider request.
7. Run `teamcode run claude -- ...` and `teamcode run codex -- ...` with a small request. Confirm request/provider/model/account metadata and settled usage in the server logs without prompts or credentials.
8. On a test workstation, run `teamcode service install`, then test reuse, stop/start/restart/uninstall. Actual Windows lifecycle CI and actual workstation reboot/logon evidence are separate checks; consult their latest recorded status.
9. Configure a fallback route in the dashboard. First use the documented portable profile with reasoning explicitly disabled and standard function tools. Confirm the destination model, then deliberately test unsupported features and inspect the error instead of assuming conversion is lossless.

## Decisions and evidence still needed for a full V1

- **Full agent portability:** a completed two-turn function call is not a coding-task acceptance result. Exercise real Claude Code and Codex tools and reasoning in both directions. Opaque signed/encrypted reasoning and custom provider tools remain explicit limitations. Native passthrough retains their original fields; crossing protocols does not currently preserve every such feature.
- **Session behavior:** shared SQL affinity and model-specific failover are implemented and integration-tested; finish real-agent quota/reset-boundary acceptance without changing an active tool conversation incompatibly.
- **Capacity control:** finish active-limit/model-alias quota handling and removed model caps, real Codex Windows hook acceptance, subagent fan-out, real-load forecast replay stability, fair interactive scheduling and reconciliation. Percentages from different plans are not interchangeable team credits.
- **Team administration:** finish groups, devices and scoped service credentials with revocation/audit acceptance.
- **Distribution and operations:** add the first open-source model adapter, versioned native distribution, update/rollback, pre-login service support, clean-machine reboot/logon and database restore/performance evidence.

Centralized MCP and skill administration remains V2. None of these remaining gates is removed merely because the foundation or a platform CI run is green.
