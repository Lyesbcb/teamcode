# Conversation affinity

TeamCode stores conversation placement in MySQL so requests reaching different replicas can reuse the same subscription and prompt cache. Affinity is a routing hint; authentication, model policy, member/pool allowance, account eligibility and physical concurrency are checked on every request.

## Identity and isolation

Claude Code sends `x-claude-code-session-id`; Codex sends `session-id`. The local proxy, SDK and server share an inference-header allowlist. The Claude-specific header takes precedence, including when it is malformed. Duplicate, empty or invalid session values are treated as no affinity; accepted values contain 1–128 ASCII letters, digits, dots, underscores or hyphens. Session headers never replace team authentication.

One client session can contain multiple subagents. Following TeamClaude's `conversation.js` and session distribution tests, the conversation key includes the opening message instead of pinning every subagent to the session's first account. Later turns keep the same key; a fresh subagent or front-compacted history gets another key. Forks with the same opening intentionally share affinity because they share a cached prefix.

TeamCode already parses bounded request JSON for policy inspection, so it hashes a bounded serialization of the first Messages element or first Responses user input. At most 64 KiB of that opening enters the digest; later turns are not serialized for this purpose. Identical bounded prefixes may share a hint without sharing conversation state. The original session ID and message content are never persisted: the table stores SHA-256 session/conversation digests and routing metadata, scoped by authenticated team and member.

## Placement and fallback

`SelectAccountUseCase` first honors the administrator's highest eligible priority. Within that tier it prefers the conversation's account for the current requested model, then another eligible account already serving that conversation on the same provider. New placements consider active requests, recent conversation load, quota utilization and a stable account-ID tie-breaker.

Each requested model has a separate hint. For example, a Sonnet-specific exhausted window can send Sonnet elsewhere without moving the conversation's Opus pin. A disabled account, exhausted relevant quota, cooldown, request exclusion, full physical slot or changed pool cannot retain a pin. A newly available higher-priority account preempts a lower-priority pin.

The last admitted provider/model target is remembered as well. A configured cross-provider fallback does not immediately bounce back to the preferred provider on every later turn. Changing the ordered target list invalidates the remembered target through a route fingerprint. Targets must still be present in current routing policy, and unsupported protocol conversions are skipped in favor of a configured compatible target. If every target is incompatible, the request fails explicitly; no fields are discarded to force compatibility.

Placement updates and quota reservations share the same team-locked SQL transaction. A placement records admission, not a claim that a provider completed the request. Definite rejection can select a replacement before output; an ambiguous or committed stream is never replayed. Concurrent turns may spill to another account when the pinned account has no physical slot, rather than bypassing its concurrency ceiling. Fair queueing remains separate work.

## Lifetime and bounds

A hint expires after one hour without admission. Conversation load counts activity during the last two minutes, matching TeamClaude's distinction between a known cache and active work. Maintenance removes expired rows in bounded batches. There are at most 1,000 hints per member and 10,000 per team; once full, new requests continue without recording another hint. Existing hints still renew, and one member cannot evict another member's active work by minting identifiers.

Account IDs are stable database IDs, never list positions. Account removal cascades its hints; disablement and pool changes take effect through admission eligibility. Session revocation is checked before both hint lookup and reservation. No hint authorizes a model, provider, pool or account.

## Evidence and remaining limits

`SessionAffinityKeyTest` covers growing conversations, independent subagents, malformed/duplicate header precedence, Responses input, route changes and bounded large openings. `SessionAffinityPersistenceTest` uses two independent MySQL connection pools to prove cache reuse, model diversion, concurrent placement, slot ceilings, priority/disable/cooldown/exclusion handling, member/pool isolation, expiry, revocation and storage bounds. `PoolGatewayTest` verifies stable cross-provider fallback, route changes and native-only request controls. API, SDK, local proxy and server tests cover header propagation.

Affinity does not persist provider-generated thread state or resume a disconnected stream. Provider-managed thread IDs remain unsupported. Complex reasoning/tool compatibility, live multi-turn coding tasks in both fallback directions and quota-reset/expiry-aware optimization still require their own acceptance; a stable account choice is not proof that arbitrary protocols are interchangeable.
