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

A cross-protocol target decodes into an internal conversation and encodes into the target protocol. Text, supported image sources, function definitions, tool call IDs, JSON arguments and tool results have explicit mappings. The streaming adapter maps content blocks/items, argument deltas, stop reasons and usage. Cached input is accounted for according to each provider's definition rather than double-counted. Raw target-provider usage remains authoritative for team credit settlement.

The currently verified cross-provider profile explicitly disables reasoning (`thinking.type=disabled` for Messages, `reasoning.effort=none` for Responses); administrators must permit the `none` effort. These two controls map to one another without substituting a different effort. Provider-default reasoning can produce opaque items and fail compatibility checks. Subscription transport metadata events are consumed without converting them into conversational content; terminal status and usage are still required.

Signed/encrypted reasoning replay, hosted tools, custom tool grammars, citations, provider-managed thread IDs, unsupported structured output and unknown semantic controls are rejected explicitly. They are not silently removed. Cross-provider compatibility does not yet imply every Claude Code or Codex feature works on every route. Compatibility acceptance must include a complete tool-call/result round trip, not merely a text greeting.

The stream decoder accepts split UTF-8/SSE framing, bounds individual events, content blocks and accumulated output, and rejects missing terminal usage. A downstream disconnect propagates without emitting a replacement response. Tests cover tool loops, empty arguments, usage/cache mapping, invalid/truncated streams, opaque reasoning, policy checks and safe versus ambiguous fallback.

Protocol references: [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming), [Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events). Subscription transport acceptance is checked separately because public API documentation does not guarantee that subscription endpoints accept every public API option.
