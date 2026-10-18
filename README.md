# TeamCode

An open-source, self-hosted model gateway for teams: pooled provider accounts, fair access, predictive capacity management, a web dashboard, and a native Kotlin Multiplatform CLI.

**Early development.** Claude Max and Codex subscription inference, native account import/proxy, team administration, weighted daily budgets, model/effort policies and capacity guidance are implemented. Real Claude Code → Haiku and Codex CLI → GPT-6 Luna acceptance runs have passed through the native proxy and central server. Explicit model routes, a limited cross-provider function-tool profile and macOS/Linux user service installation are implemented; opaque reasoning compatibility, Windows and system services remain in progress. See [implementation status](docs/status.md) before using a command from the [product roadmap](docs/product.md).

## Run the team server

Use JDK 21 and MySQL 8.4. The server applies Flyway migrations at startup:

```sh
./gradlew :app:buildFatJar :cli:linkDebugExecutableMacosArm64
export TEAMCODE_DATABASE_URL=jdbc:mysql://localhost:3306/teamcode
export TEAMCODE_DATABASE_USER=teamcode
# Set TEAMCODE_DATABASE_PASSWORD from your secret store.
# Generate once, store securely, and reuse across restarts and restores:
export TEAMCODE_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export TEAMCODE_PUBLIC_URL=https://teamcode.example.org
java -jar app/build/libs/app-all.jar
```

Put an HTTPS reverse proxy in front of port 8080, or use `http://localhost:8080` as the public URL for local development. `TEAMCODE_HOST` defaults to loopback. The public URL must match the browser origin for form protection.

On an empty database, TeamCode creates `admin` and prints its random temporary password **once** to the bootstrap log. Sign in at `/dashboard` and change it. Restarting does not print or reset it. There is no public registration, email setup or OAuth requirement for team membership. Administrators create users and receive a one-time temporary password to share privately. Password changes and resets revoke every existing session; disabling or changing a member's role also revokes their sessions. The last active administrator cannot be disabled or demoted.

```sh
teamcode login --server https://teamcode.example.org
teamcode accounts add
teamcode accounts import --from teamclaude --dry-run
teamcode accounts import --from teamclaude
teamcode accounts list
teamcode accounts handover ACCOUNT_ID --confirm
teamcode logout
```

The CLI prompts for username/password, handles required password changes, and saves only the server URL and revocable session token. Provider sign-in uses a local PKCE flow: open the URL in a browser and paste the complete `code#state` into the hidden prompt. Credentials are sent to TeamCode, where the Claude profile/Max subscription or Codex authenticated subscription identity is checked before encrypted storage. Import reads TeamClaude/TeamRouter's XDG configuration or `--config PATH` and never modifies the source. Existing accounts keep their ID, custom name, disabled state and priority. Stop any other credential refresher before explicit `handover --confirm` or import `--take-ownership`. Expired Claude credentials require `accounts add`; expired Codex credentials currently require signing in with Codex/TeamClaude and importing again. Direct Codex OAuth enrollment is pending. For a temporary trial while TeamClaude still owns refresh, import with `--access-only`: TeamCode uses the current access tokens and refuses to refresh them. Reimport current credentials when they expire.

## Kubernetes

The [Helm chart](helm/teamcode/README.md) follows the `pkg` chart conventions, with two stateless server replicas, streaming ingress settings, a persistent optional MySQL 8.4 instance, external database support, HPA, probes and draining. All replicas share database-backed sessions, quotas, refresh ownership and worker leases; see [replica behavior](docs/replicas.md). Redis and sticky sessions are not required. The image workflow publishes `nathanfallet/teamcode:latest` and immutable commit tags after verification.

## Model routes

Administrators can configure ordered model replacements and fallbacks in **Dashboard → Routes**, such as `claude-opus-*` to `anthropic` then `codex:gpt-6-astra`. Requests without a matching rule retain their native provider. Both requested and resolved models must pass the team's policy. See [routing and compatibility](docs/routing.md) for the supported translation profile, bounded retries and limitations; a cross-provider route does not imply every agent tool or reasoning format is portable.

## Local service

After login, `teamcode service install` starts a local proxy automatically at user login on macOS/Linux. `teamcode run claude -- …` and `teamcode run codex -- …` reuse the authenticated service when available. Use `service status`, `service logs`, `service stop`, `service start`, `service restart` or `service uninstall` to manage it. Installation requires a stable native binary path and preserves your saved login on uninstall. See [service behavior and verification](docs/services.md); Windows runtime acceptance is in progress; startup before login remains planned. See the [Windows workstation guide](docs/windows.md).

## Try the transport fixture

Build with JDK 21 using the checked-in Gradle wrapper:

```sh
./gradlew :app:buildFatJar :cli:linkDebugExecutableMacosArm64
export TEAMCODE_MODE=fixture
# Generate a private random value; do not use the examples as production credentials.
export TEAMCODE_ACCESS_TOKEN="$(openssl rand -hex 32)"
java -jar app/build/libs/app-all.jar
```

In another terminal, with the same test access token available:

```sh
printf '%s\n' "$TEAMCODE_ACCESS_TOKEN" | \
  ./cli/build/bin/macosArm64/debugExecutable/teamcode.kexe configure --server http://127.0.0.1:8080
./cli/build/bin/macosArm64/debugExecutable/teamcode.kexe status
export TEAMCODE_LOCAL_TOKEN="$(openssl rand -hex 32)"
./cli/build/bin/macosArm64/debugExecutable/teamcode.kexe proxy start
```

The CLI saves the self-hosted server URL and authentication in `$XDG_CONFIG_HOME/teamcode/config.json`, falling back to `~/.config/teamcode/config.json` (directory 0700/file 0600 on POSIX). Other invocations reuse it. Omit `--server` to be prompted for the URL. `configure` is a development helper for an existing test token. Normal installations use `login`. Do not enter secrets into shell history or command arguments.

The proxy accepts authenticated JSON `POST /v1/messages` and `POST /v1/responses` on `127.0.0.1:8789`. In server mode it routes to eligible imported Claude Max or Codex subscription accounts; in fixture mode it returns synthetic events. Point Claude Code at this loopback origin with `ANTHROPIC_BASE_URL` and use the local proxy token as `ANTHROPIC_API_KEY`. Host/port environment variables for the central fixture: `TEAMCODE_HOST` (default loopback) and `TEAMCODE_PORT` (default 8080).

## Verification

```sh
python3 scripts/check-module-graph.py
./gradlew :api:jvmTest :client:jvmTest :domain:test :infrastructure:test :presentation:test :cli:jvmTest
python3 scripts/smoke-native.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar
```

The smoke test starts and stops only its own processes, uses an isolated configuration directory and synthetic credentials, and checks private file permissions, authentication and progressive SSE. Native targets are declared for desktop macOS/Linux/Windows. macOS and Linux native runtime checks pass; Windows has native configuration, process and service adapters under runtime acceptance. MySQL integration tests require Docker. `scripts/smoke-team.py` verifies the team server with its own isolated MySQL and the native CLI. CI verifies the JVM and runs native transport and service checks on macOS/Linux/Windows; it does not use provider credentials. Each platform uploads its development binary only after its acceptance tests pass.

## Architecture

```text
cli -> client -> api
app -> presentation -> domain
app -> infrastructure -> domain
presentation -> api
```

Seven modules, root package `me.nathanfallet.teamcode`. The CLI is Kotlin/Native, not a JVM wrapper. The server is Ktor/JVM. No private dependencies or ControlResell services are required.

Read [architecture](docs/architecture.md), [product](docs/product.md), [roadmap](docs/roadmap.md) and [contributing](CONTRIBUTING.md). Original code is Apache-2.0.

## Capacity and model policies

The dashboard exposes member weights, per-member concurrency and optional hard daily caps, pool budgets, observed provider quota windows, model/effort restrictions and current agent guidance. Daily credits are an internal team budget, not a conversion of Max subscription percentages. With weights 5/5/1 and a budget of 1,100 credits, members receive 500/500/100; changing weights does not erase spend. Reservations and actual usage survive restarts. Separate pool identifiers isolate accounts for protected members.

Default model policy permits `claude-*` and `gpt-*` (existing saved policies are not broadened) and denies `*fable*`. Administrators can set allowed/denied patterns and reasoning efforts in `/dashboard/models`. Requests are rejected before dispatch when a policy is violated. No effort is silently replaced.

`teamcode agents watch` prints expiring guidance updates. `teamcode agents hint --format claude` emits Claude Code PreToolUse additional context; receipt records mean delivery by the CLI, not proof that the model complied. Server admission enforces concurrency and pacing independently. See [capacity behavior](docs/capacity.md).

Real-provider acceptance is explicitly opt-in and never runs in CI:

```sh
python3 scripts/acceptance-provider.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar \
  --source /path/to/teamclaude.json
```

It starts its own MySQL/server/proxy, imports in access-only mode, verifies a repeated import, asks an isolated real Claude Code process for one short Haiku response, checks metadata-only server logs and destroys its own test database. It never edits the source config or refreshes provider credentials.

## Claude Code through the local proxy

After signing in to TeamCode and importing/activating an eligible Claude Max account:

```sh
teamcode run claude -- --dangerously-skip-permissions
teamcode run claude -- --model claude-haiku-4-5-20251001 -p "Hello"
```

`run` reuses a matching authenticated TeamCode service when available; otherwise it starts a dedicated proxy on a free loopback port. It launches the installed agent from your PATH, forwards the arguments after `--` literally and preserves your working directory and terminal. The dedicated proxy closes when the agent exits; a shared service stays running. The agent's exit status is returned. URL/session settings come from your saved TeamCode configuration; personal agent configuration files are not edited. Each dedicated proxy gets its own local capability. Native `run` is verified on macOS/Linux; Windows runtime acceptance is in progress.

For a manually managed proxy:

```sh
export TEAMCODE_LOCAL_TOKEN="$(openssl rand -hex 32)"
teamcode proxy start
# In another terminal with the same TEAMCODE_LOCAL_TOKEN:
ANTHROPIC_BASE_URL=http://127.0.0.1:8789 \
ANTHROPIC_API_KEY="$TEAMCODE_LOCAL_TOKEN" \
claude --model claude-haiku-4-5-20251001
```

Remove conflicting `ANTHROPIC_AUTH_TOKEN` or `CLAUDE_CODE_OAUTH_TOKEN` environment overrides before using this manual setup. The local capability protects the loopback proxy; the proxy uses your saved TeamCode session to reach the team server. Provider credentials remain on the server. The requested model and effort must satisfy your team's policy. Native Messages fields and tool definitions pass through unchanged.

## Codex through the local proxy

```sh
teamcode run codex --
teamcode run codex -- exec --model gpt-6-luna "Explain this repository"
```

The launcher supplies the custom TeamCode provider for this invocation, using your saved server configuration and a temporary loopback capability. All remaining CLI arguments are passed through. An explicit client-side provider override can override these launch settings; server policy applies only to requests sent through TeamCode.

For a manually managed proxy, use a custom provider pointed at `http://127.0.0.1:8789/v1`, with `wire_api = "responses"` and `env_key = "TEAMCODE_LOCAL_TOKEN"`. Select `model_provider = "teamcode"` and an explicit model available on the imported account. These settings can be passed per invocation without editing personal configuration. The [official custom provider documentation](https://learn.chatgpt.com/docs/config-file/config-advanced#custom-model-providers) describes the client configuration.

The subscription adapter uses ChatGPT's Codex endpoint, never the metered OpenAI API. It requires streamed requests with complete conversation input; provider-managed previous-response/thread state is currently rejected. Tool definitions and native Responses fields are preserved. Usage accounting includes cached input and reasoning output exactly once.

To exercise the actual Codex CLI instead of Claude in the opt-in acceptance script, add `--skip-claude --codex-model gpt-6-luna`. Model availability depends on the subscription. Native Codex-to-Codex transport is verified; Claude-to-Codex translation is not yet enabled.
