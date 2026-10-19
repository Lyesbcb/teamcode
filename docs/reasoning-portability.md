# Experimental default-reasoning portability acceptance

Cross-provider coding tasks currently pass with the portable profile, which explicitly disables reasoning. At the owner's request, cross-provider conversion is now experimental, disabled by default and excluded from V1 release acceptance. Its default-reasoning compatibility remains unfinished. Native same-protocol requests preserve provider-specific fields; translating a request is a separate capability.

## Reproducing the gate

The opt-in real-provider fixture accepts `--coding-reasoning portable|default` with `--coding-task claude|codex|both`. Portable remains the default for compatibility with earlier evidence. The default profile removes an inherited `MAX_THINKING_TOKENS` override and adds no reasoning-disable settings. Both profiles still isolate agent homes, personal hooks/MCP, tools and the task workspace. This is a test of default reasoning settings, not every client feature or personal configuration.

For example, after building the server and native CLI, using an authorized TeamClaude configuration:

```sh
python3 scripts/acceptance-provider.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar \
  --source "$HOME/.config/teamclaude.json" \
  --skip-claude --skip-reimport \
  --coding-task claude --coding-reasoning default \
  --codex-model gpt-6-luna
```

Use `--claude`/`--codex` to select actual installed executables when terminal integrations insert shims. The fixture imports access-only credentials into its disposable encrypted database, never refreshes them, and removes its own database/processes on exit. Real-provider tests are opt-in and must never be part of ordinary CI. The coding fixture explicitly enables the experiment in its disposable server. Selecting `default` must not silently fall back to the portable profile to obtain a passing result.

## Observed failures on 2026-10-19

The installed Claude Code 2.1.302 and Codex 0.163.0 were tested individually against forced cross-provider routes. Both coding tasks failed before a provider inference dispatch; neither changed the task source. These are failures of experimental compatibility acceptance, not accepted portability cases.

A separate loopback-only capture, without real provider traffic, observed these configuration fields:

- Claude/Haiku: `context_management.edits=[{type: "clear_thinking_20251015", keep: "all"}]`, manual `thinking.type="enabled"` with `budget_tokens=31999`; the first request also used `thinking.display="updates"`. The current Messages decoder rejects `context_management` before reaching its explicit-budget guard.
- Codex/Luna: `reasoning={effort: "medium", context: "all_turns"}`, `include=["reasoning.encrypted_content"]`, `store=false`, `text.verbosity="low"`. The current Responses decoder accepts `all_turns` only with effort `none` and rejects this default request before dispatch.

Removing only the first rejected field would not complete the work. The adapters also lack signed/encrypted history representation and thinking/summary stream events. A successful first prompt would not prove a tool loop, reasoning replay or fallback. Acceptance needs completed code tasks, independently checked results, known settled usage, correct reasoning handling across multiple tool boundaries, and the controlled native-to-fallback scenario in both directions.

## TeamClaude reference and constraints

The sibling reference explicitly states that providers are passthroughs, not a translation layer (`src/provider.js`). `test/provider-routing.test.js` verifies strict subscription provider partitions. `src/model.js:parseRequestEffort` and `test/model.test.js` read effort only from the provider's request-level field and leave omitted effort unknown. TeamCode adds allowlists and quota enforcement, but must preserve that distinction rather than inventing an equivalent default effort.

TeamClaude's `src/server.js:rewriteRequestBody` supports opt-in per-account `stripRequestFields` for strict Anthropic-compatible third-party upstreams, including `context_management`. That is not evidence that silently stripping fields is a valid Claude/Codex conversion. TeamCode's native passthrough should continue preserving supported first-party fields; cross-provider handling needs explicit, tested semantics.

[Claude thinking troubleshooting](https://platform.claude.com/docs/en/build-with-claude/thinking-troubleshooting) documents unchanged replay of signed thinking and prefix constraints. [OpenAI's reasoning guide](https://developers.openai.com/api/docs/guides/reasoning?api-mode=chat) describes encrypted reasoning items for stateless continuation. Provider-private reasoning cannot be converted by renaming its fields or fabricating a signature. Keep its original representation for compatible replay; define and verify how a different provider receives the visible conversation and supported reasoning controls. Do not claim preservation until multi-turn tests prove the actual implementation.
