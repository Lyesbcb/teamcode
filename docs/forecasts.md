# Quota forecast snapshots

Interactive administrators can open **Dashboard → Forecasts** (`/dashboard/forecasts`) and select a subscription account. `GET /api/v1/forecasts?account=ACCOUNT_ID` returns the same observed series and window estimates; omitting `account` selects the first listed account. The typed SDK exposes `TeamCodeClient.forecasts(accountId = ...)`. Ordinary members and service/CI keys cannot read these views, including administrator-owned service keys. Every read revalidates shared authorization and account/team ownership. Responses are private and use `Cache-Control: no-store`.

This is a read-only snapshot view. Refresh the selected account to obtain a new snapshot. Automatic/SSE updates remain planned V1 work; the current page does not claim live delivery. The charts remain available in controller observation mode and never enable predictive enforcement or send an agent directive.

## Measurements and projections

Each card is one provider-observed window on the selected account. The current percentage, observation/reset timestamps, learned percentage points per hour, pressure estimate and sample coverage remain distinct. Percentages from different accounts or plans are never added, and an account window estimate is not a promise of model access or the member's controller decision. Current routing, model/effort policy, pools, account health, weighted allowance and scoped hysteresis still determine actual advice/admission.

The solid curves contain actual measurements from the latest hour. The server retains at most one sample per minute/window; historical retention remains seven days. Resets, utilization decreases, duplicate timestamps and gaps longer than five minutes split the curve. Hover a point for its measured value and UTC time. The same domain segmentation and window estimator are used for predictive control: learning uses only the newest continuous segment, with at least three samples spanning a minute and a known reset. A clean segment after a decrease or measurement gap can start learning again without averaging the earlier segment into its rate.

Dashed curves are conditional projections, stopping at the earliest of the pressure threshold, reset or one hour into the future. The pressure threshold is currently 90%; `EXHAUSTED` in the window API means that pressure threshold has been measured, not that a provider has necessarily rejected the account. The independent provider-window admission exclusion remains 95%. The UI formats the pressure ETA approximately to the minute; raw timestamps remain available to API clients.

Unknown resets, missing rates, old/future readings and absent measurements are explicit. No observed window is fabricated with 0% utilization. A valid zero learned speed is displayed separately from an unknown speed. An empty account list and an account without readings have their own empty states. Sample count/span describes coverage, not a fabricated statistical confidence score. A window that resets first is labeled accordingly; its beyond-reset pressure extrapolation is not presented as an event in the current window.

## Bounds and shared persistence

The selector lists at most 64 account labels, plus an explicitly selected account if it lies outside that subset. A response contains at most 128 current windows and 8,192 history rows; `truncated: true` reports a bound being reached. The service reads only account labels/status, observed windows and history, with no credential envelope, provider identity fields or decryption key. Retired model windows are absent; reintroduced windows cannot reuse samples from their retired incarnation.

The service holds the team lock while reading current windows and their history boundaries. Parameterized Exposed queries then use fixed per-window time ranges on the existing `quota_recent(account_id, window_name, observed_at)` index. Reads stay compatible with independent server replicas and use the database clock, without process-local forecast caches. No schema change is needed for this view.

A local MySQL 8.4 diagnostic uses [Exposed's `explain` API](https://jetbrains.github.io/Exposed/api/exposed-jdbc/org.jetbrains.exposed.v1.jdbc/-jdbc-transaction/index.html) on the actual production query, including 4,096 retained out-of-range samples. The initial join scanned approximately 4,100 entries to return 18 rows (4.04 ms in that fixture). The final fixed-range query reads 18 entries through `quota_recent` and returns the same 18 rows (0.117 ms). These are isolated fixture measurements, not production throughput, lock-contention measurements or SLOs.

## Local preview and acceptance

Generate a standalone synthetic preview through the real server renderer:

```sh
./gradlew :presentation:previewForecastDashboard
```

Open `presentation/build/preview/forecasts.html` in a browser. The preview is explicitly marked synthetic; it uses no server login, operator database or provider. The production page uses server-rendered SVG, local point tooltips and escaped labels, with no chart script, external chart service or relaxed script policy.

`ForecastContinuityTest` reproduces the stale-gap/rate-relearning defects and verifies segmented histories and conservative future readings. `ForecastPersistenceTest` uses actual MySQL and independent pools for empty states, rate/reset/freshness behavior, 4,096 old rows, model-cap retirement/reintroduction, output bounds, foreign-team isolation and revoked/scoped/member denial. Its execution plan comes from the production query itself. `ForecastRoutesTest` covers the typed SDK, admin-only API/dashboard access, strict account queries, private headers and hostile labels. `ForecastChartTest` checks parseable accessible SVG, segment boundaries, unknown/stale projection suppression, reset clipping, locale-independent coordinates and empty data. The restoration HTTP fixture and packaged two-process smoke also exercise the endpoint.

See [current evidence](status.md). Sustained real-agent forecast accuracy, outside-spend attribution, live/SSE updates, wider browser acceptance and the other V1 requirements remain open.
