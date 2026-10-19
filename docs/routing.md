# Model routing and protocol translation

Routes are administrator-owned team configuration, stored in MySQL and shared by every server replica. Manage them in **Dashboard → Routes**, or through `/api/v1/routes`. A rule has a stable ID, a requested-model glob, priority, ordered targets, enabled state and a version. Saving or deleting an outdated version returns HTTP 409. There are at most 64 rules and four targets per rule.

The first enabled rule, sorted by descending priority and then ID, wins. Targets are explicit and are not recursively resolved through other rules. With no matching rule, Messages uses Anthropic and Responses uses Codex. No fallback is installed automatically.

Examples (verify exact model availability for your accounts):

| Requested model pattern | Ordered targets |
| --- | --- |
| `claude-opus-*` | `anthropic`, `codex:gpt-6-astra` |
| `claude-sonnet-*` | `anthropic`, `codex:gpt-6-sol` |
| `claude-haiku-*` | `anthropic`, `codex:gpt-6-luna` |
| `gpt-6-astra` | `codex`, `anthropic:claude-opus-4-7` |

Omitting a target model retains the requested model. Custom aliases need a matching route and must also be permitted by the model policy. Both the requested model and the selected target model are checked; an alias cannot hide a forbidden model. Efforts are carried explicitly, without silently lowering or raising them. Incompatible effort values fail at the provider or compatibility check.

## Admission and fallback

Every attempt uses the member's assigned pool, weight, hard limits, concurrent limit and pacing directive. Fallback cannot escape a protected pool or a depleted member allowance. Account-specific quota windows use the resolved model.

A request tries eligible accounts for the preferred target before moving to the next configured target. Unusable credentials before dispatch and explicit HTTP 401/403/429 rejection before streaming starts trigger another selection without forwarding the provider rejection to the client. Rejected accounts enter cooldown; these definite pre-generation rejections release their reservation with zero charge and do not trigger member pacing between attempts. Each account is attempted at most once per request.

After all configured targets have no eligible account, TeamCode returns HTTP 429 `pool_unavailable`. Member/team allowances, concurrency and pacing limits can independently return HTTP 429 even when a subscription has capacity; fallback must respect these policies. A request is bounded to 64 distinct accounts and a ten-minute overall deadline. Hitting the account bound returns HTTP 503 `routing_attempt_limit`, which does not assert that all capacity has been exhausted.

HTTP 5xx, transport interruptions and any failure after committed output are not replayed. Their usage stays conservatively reserved unless terminal usage is known. This avoids duplicate model work and duplicated tool actions after an ambiguous result. Conversation affinity is stored in SQL and shared by replicas; see [session affinity](session-affinity.md). Callers still send complete conversation history, and incompatible opaque history blocks a provider switch. A configured incompatible target can be skipped for a compatible native target without dropping fields.

## Conversion boundary

For a target using the incoming protocol, the request fields and response stream remain native; only an explicitly configured model replacement changes the model field. Native calls do not pass through the common conversation representation.

A cross-protocol target decodes into an internal conversation and encodes into the target protocol. Text, supported image sources, function definitions, tool call IDs, JSON arguments and tool results have explicit mappings. A failed Messages tool result becomes a Responses tool output with an explicit `is_error=true` text marker followed by every original content block; the error stays within the tool result and never becomes a system instruction. The streaming adapter maps content blocks/items, argument deltas, stop reasons and usage. Cached input is accounted for according to each provider's definition rather than double-counted. Raw target-provider usage remains authoritative for team credit settlement.

Messages function tools default to `strict=false` when translated. This preserves optional arguments: leaving strictness unspecified in Responses can normalize the schema into strict mode and make those arguments required. Explicit `strict=true` is preserved. See [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling?strict-mode=disabled) and [Claude strict tools](https://platform.claude.com/docs/en/agents-and-tools/tool-use/strict-tool-use).

Explicit neutral sampling defaults (`temperature=1`, `top_p=1`) map to the target's defaults without forwarding unsupported Codex subscription parameters. Custom sampling values remain a compatibility error. Claude Code sends temperature 1 even for an otherwise default request; [Claude's Messages reference](https://platform.claude.com/docs/en/api/http/beta/messages/create) documents this default. This normalization is covered by the real subscription coding acceptance below, not inferred solely from public API compatibility.

The currently verified cross-provider profile explicitly disables reasoning (`thinking.type=disabled` for Messages, `reasoning.effort=none` for Responses); administrators must permit the `none` effort. These two controls map to one another without substituting a different effort. Provider-default reasoning can produce opaque items and fail compatibility checks. Subscription transport metadata events are consumed without converting them into conversational content; terminal status and usage are still required.

Signed/encrypted reasoning replay, hosted tools, unsupported custom-tool grammars, citations, provider-managed thread IDs, unsupported structured output and unknown semantic controls are rejected explicitly. They are not silently removed. Cross-provider compatibility does not yet imply every Claude Code or Codex feature works on every route. Compatibility acceptance must include a complete tool-call/result round trip, not merely a text greeting.

Responses function and custom text tools can be declared at the top level or in developer-role `additional_tools` items, including a single namespace level. Namespace and tool names receive deterministic, collision-checked Claude wire aliases. The original namespace, name and call ID are restored in Responses output and preserved when the client replays its history. Bindings belong to one request; aliases do not depend on a replica-local registry. Duplicate declarations must agree. See the [Responses item definitions](https://developers.openai.com/api/reference/resources/conversations/subresources/items/methods/create) and [OpenAI tool formats](https://developers.openai.com/api/docs/guides/function-calling).

Custom text input is represented to Claude as a strict object containing one `input` string. The stream adapter buffers this object within the existing output limit, validates it, then emits the original text through `response.custom_tool_call_input.delta`/`done` events. Invalid wrappers or Unicode fail without emitting executable custom input or a successful tool completion. Supported formats are free text, the unrestricted regexes `[\s\S]*` and `[\s\S]+`, and the exact Codex code-mode Lark grammar whose `plain_source` accepts any nonempty text. Nonempty constraints are retained. Other grammars require a real validator and currently fail before dispatch; a prompt asking the model to follow a grammar is not treated as validation.

Responses verbosity (`low`, `medium`, `high`) becomes an explicit system instruction expressing the requested response length; this is an instruction mapping, not a guarantee of identical provider behavior. `reasoning.context=all_turns` is accepted only with explicit `effort=none`. Client transport metadata is not turned into conversation content. Empty/null output `annotations` and `logprobs` can be replayed; actual citations and token probabilities still fail explicitly.

The stream decoder accepts split UTF-8/SSE framing, bounds individual events, content blocks and accumulated output, and rejects missing terminal usage. A downstream disconnect propagates without emitting a replacement response. Tests cover tool loops, empty arguments, usage/cache mapping, invalid/truncated streams, opaque reasoning, policy checks and safe versus ambiguous fallback.

Protocol references: [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming), [Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events). Subscription transport acceptance is checked separately because public API documentation does not guarantee that subscription endpoints accept every public API option.


## Real coding-task acceptance

`acceptance-provider.py --coding-task claude|codex|both` runs actual coding clients through the native launcher and central server, with forced cross-provider routes in either direction. It imports authorized subscriptions in access-only mode into its own disposable database, creates a small broken Python module and unchanged tests, and requires actual tool execution, corrected source, four independently rerun passing tests, multiple provider dispatches and settled usage. Claude uses Read/Edit/Bash; Codex uses its normal namespaced/code-mode tool inventory. Only metadata and matched public error categories are printed; raw agent output and subscription credentials are withheld. Process lifetime is bounded for both clients; Claude additionally has a twelve-turn bound. Test resources are removed afterward.

Example from a local build (never run this in CI):

```sh
python3 scripts/acceptance-provider.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar \
  --source /path/to/authorized/teamclaude.json \
  --coding-task claude --claude /path/to/actual/claude
```

Verified with Claude Code 2.1.302 requesting `claude-haiku-4-5-20251001`, routed to the Codex subscription model `gpt-6-luna`: eight requests settled and all four independent tests passed. This uses the portable reasoning-disabled profile and isolated built-in tools; it is not full-default-client acceptance or proof of a mid-conversation provider switch.

For the reverse direction, use `--coding-task codex --codex /path/to/actual/codex`. Codex 0.163.0 requesting `gpt-6-luna` completed the same coding task through the real Claude Max model `claude-haiku-4-5-20251001`: sixteen requests settled, the source was corrected, the test file was unchanged and all four independent tests passed. This exercises `additional_tools`, namespaced function/custom text tools, replayed history and verbosity. Reasoning was explicitly disabled and the destination was forced from the first turn. Full-default reasoning, other tool grammars and actual quota/reset-boundary fallback remain V1 acceptance work. Controlled mid-conversation acceptance is described below.


## Mid-conversation fallback acceptance

Add `--coding-fallback` to a coding-task run to start on the agent's native provider and configure the opposite provider as its fallback. A loopback fixture waits for an actual tool call and matching result in the client's request history. Before forwarding that request unchanged, it disables the native provider's accounts through the management API in the disposable database. It does not change the route or inject a fake inference response. Account settings are restored on exit, including after failure.

```sh
python3 scripts/acceptance-provider.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar \
  --source /path/to/authorized/teamclaude.json \
  --coding-task claude --coding-fallback --claude /path/to/actual/claude
```

Every client inference must return HTTP 200 from the expected provider/model. Response request IDs correlate to provider dispatches and usage settlements; extra attempts are allowed only when they settled with zero charge before a safe retry. The route must retain its original version and targets. The same source-change, unchanged-test and four independent test assertions apply. The fixture's HTTP forwarding, exact history preservation, authentication and cleanup have provider-free tests in CI.

Removing account availability exercises a real conversation switch without intentionally exhausting a subscription. It is not proof of an actual provider quota reset, a signed/encrypted reasoning transfer or every client default. These remain separate acceptance gates.

Verified on macOS ARM64 with reasoning disabled:

| Client | Native start | Fallback after completed tool | Result |
| --- | --- | --- | --- |
| Claude Code 2.1.302 | One Claude Haiku request | Nine Codex `gpt-6-luna` requests | Source corrected, tests unchanged, four independent tests passed |
| Codex 0.163.0 | One Codex `gpt-6-luna` request | Eleven Claude Haiku requests | Source corrected, tests unchanged, four independent tests passed |

Every response in these runs was HTTP 200 with successful terminal usage, and every dispatch was correlated to a response request ID. No route edit or source credential refresh was used. Codex acceptance uses `--coding-task codex --coding-fallback --codex /path/to/actual/codex`; `--coding-task both` runs the directions sequentially and restores account availability between them.
