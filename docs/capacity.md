# Capacity, policies and agent guidance

## Two independent limits

Subscription windows are provider observations: account-wide five-hour and weekly windows, plus model-specific windows when reported. TeamCode never sums percentages across plans or invents a token-to-subscription exchange rate. An account at 95% of a relevant window is excluded until its reset. HTTP 429 adds a bounded cooldown; credential rejection requires reconnect.

Team credits are an administrator-controlled daily budget. Current accounting is input tokens plus cached/read/creation input tokens plus five times output tokens. The request reserves raw request bytes + 16,384 + five times maximum output tokens before dispatch. Actual usage replaces the reservation on complete streams; missing usage or interruption retains the estimate. Failed admission spends nothing. A provider 4xx settles to zero. This conservative estimate can reject a large request even if its eventual output would be short.

Daily periods reset at 00:00 UTC. Weight shares are computed over enabled members in each pool, including administrators and members still awaiting their first password change. Weight zero grants no admission. An optional hard cap further limits a member's share; zero means no additional hard cap. All members' spend also counts against the total pool budget. Changing member weights or pools never resets existing spend. Historical reservations retain their original pool.

Budgets and reservations persist in MySQL. Member requests are admitted under a team lock, so concurrent arrivals cannot each claim the same remaining credit. A physical account admits at most two active requests. Active leases expire after 15 minutes; requests time out after 10 minutes. Expiring a lease releases concurrency, not its reserved credits. The server is currently a single-team deployment; the schema also keeps team ownership explicit.

## Forecasting

Usage is sampled sequentially, with at most eight accounts per sampling pass. Inference headers supply additional observations. History retains one sample per minute per account/window, with seven-day retention; forecasts use the latest hour and matching reset epoch. At least three observations spanning one minute are required to infer a rate. Decreasing utilization, missing windows and observations older than five minutes yield unknown/stale guidance rather than optimistic headroom.

The controller compares projected exhaustion to reset for every observed window. It emits NORMAL, SLOW_DOWN, CAN_INCREASE, PAUSE or UNKNOWN. A two-minute hysteresis limits transitions except urgent exhaustion. Advice expires after three minutes without renewal. SLOW_DOWN limits admission to one concurrent request with a ten-second gap; PAUSE uses a thirty-second gap. The 95% account exclusion is enforced separately. Advice to increase parallelism only applies to useful work already queued and remains bounded by the administrator's concurrency limit.

Generic advice conservatively includes model-scoped windows. A nearly exhausted model-specific window can therefore slow unrelated requests; per-route forecasts are a follow-up. Forecast accuracy and oscillation under real load still require validation. This is an initial controller, not a claim of optimal scheduling.

## Agent integration

`teamcode agents watch` polls for changed directives and prints JSON lines. A caller can use `teamcode agents hint --format claude` as a Claude Code `PreToolUse` command hook. It returns `hookSpecificOutput.additionalContext` using the [Claude Code hook protocol](https://code.claude.com/docs/en/hooks). Hook network failures produce no model text and do not stop the agent; server admission remains authoritative.

Current hint output repeats on invocation; automatic hook installation and per-session deduplication are still pending. Do not install it on every frequent tool until sparse delivery is implemented. Receipt stages distinguish received-by-CLI from emitted-to-hook. Neither stage claims the model read, accepted or obeyed advice. TeamCode never kills agent processes.

## Models and effort

Administrators edit the allow/deny patterns and allowed efforts in the dashboard. Deny takes precedence. Missing effort is represented as `provider-default`; an explicit token thinking budget also requires `budgeted` permission and must fit the administrator's cap. This policy validates the requested and resolved model names, so route aliases cannot bypass a forbidden model. The currently enabled gateway serves native explicit Claude or GPT model IDs on their respective endpoints; cross-provider routing is still under implementation.

## Refresh ownership

New/imported accounts are pending until explicit activation. Normal handover confirms other token refreshers have stopped and gives TeamCode refresh ownership. Database row locks serialize refresh across server processes and prevent concurrent import from overwriting a newly rotated token. An uncertain refresh never reuses the old refresh token.

`accounts import --from teamclaude --access-only` is a temporary trial mode. It enables inference and quota reads using current access tokens, but never rotates them. At expiry, requests fail until credentials are imported again. Existing source agents can continue refreshing their own copy. Import never edits or stops the source application.
