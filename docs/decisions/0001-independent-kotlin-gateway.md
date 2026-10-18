# ADR 0001: Independent Kotlin gateway

Date: 2026-10-18. Status: accepted direction, implementation in progress.

Use a standalone Kotlin/Ktor server, web dashboard and native KMP CLI. Gradle group/package: `me.nathanfallet.teamcode`. Keep `cli -> client -> api` and separate domain, infrastructure, presentation and app server modules. Publish under the personal `nathanfallet/teamcode` repository with English project content.

A fork of TeamClaude gets Claude Max pooling working sooner but requires continuing JavaScript integration while our product grows into team identity, predictive pacing and protocol translation. A Ktor facade preserves that engine but splits policy authority; hard reserved pools need disjoint backend instances or internal changes. A custom server has the highest provider-compatibility cost but one authority over account selection, budgets, routing and agent directives. The expanded product justifies accepting that cost.

TeamClaude remains a behavioral reference, not a runtime dependency. Verify transport before building management polish. Keep tests deterministic; introduce providers and advertised compatibility only with evidence. Use Apache-2.0 for original code, preserving notices for any third-party material reused.

Costs: ownership of provider OAuth/streaming quirks; Native platform testing and distribution; one server plus database; no assumed extra subscription, paid API or GPU. Hosting cost depends on measured workload. Initial single-instance scope avoids distributed coordination debt. Rollback never runs two refresh owners for the same account; retain the previous installation while piloting independently.
