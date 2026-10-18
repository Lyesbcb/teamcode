# TeamCode

An open-source, self-hosted model gateway for teams: pooled provider accounts, fair access, predictive capacity management, a web dashboard, and a native Kotlin Multiplatform CLI.

**Early development.** Team authentication, member administration, encrypted provider enrollment, and the native CLI are implemented. Inference still uses the explicit synthetic transport fixture; real pool routing is the next slice. See [implementation status](docs/status.md) before using a command from the [product roadmap](docs/product.md).

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

The CLI prompts for username/password, handles required password changes, and saves only the server URL and revocable session token. Provider sign-in uses a local PKCE flow: open the URL in a browser and paste the complete `code#state` into the hidden prompt. Credentials are sent to TeamCode, where the provider identity and Max subscription are checked before encrypted storage. Import reads TeamClaude/TeamRouter's XDG configuration or `--config PATH` and never modifies the source. Existing accounts keep their ID, custom name, disabled state and priority. Stop any other credential refresher before explicit `handover --confirm` or import `--take-ownership`. Expired credentials require `accounts add`.

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

The proxy accepts authenticated JSON `POST /v1/messages` on `127.0.0.1:8789` and streams the explicit synthetic fixture. It is not ready to run real coding agents. Host/port environment variables for the central fixture: `TEAMCODE_HOST` (default loopback) and `TEAMCODE_PORT` (default 8080).

## Verification

```sh
python3 scripts/check-module-graph.py
./gradlew :api:jvmTest :client:jvmTest :domain:test :infrastructure:test :presentation:test :cli:jvmTest
python3 scripts/smoke-native.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar
```

The smoke test starts and stops only its own processes, uses an isolated configuration directory and synthetic credentials, and checks private file permissions, authentication and progressive SSE. Native targets are declared for desktop macOS/Linux/Windows; macOS ARM64 has been verified locally. Windows private configuration is deliberately unavailable until an owner-only DACL adapter is implemented. MySQL integration tests require Docker. `scripts/smoke-team.py` verifies the team server with its own isolated MySQL and the native CLI. The CI verifies the JVM and native transport on macOS/Linux; it does not use provider credentials.

## Architecture

```text
cli -> client -> api
app -> presentation -> domain
app -> infrastructure -> domain
presentation -> api
```

Seven modules, root package `me.nathanfallet.teamcode`. The CLI is Kotlin/Native, not a JVM wrapper. The server is Ktor/JVM. No private dependencies or ControlResell services are required.

Read [architecture](docs/architecture.md), [product](docs/product.md), [roadmap](docs/roadmap.md) and [contributing](CONTRIBUTING.md). Original code is Apache-2.0.
