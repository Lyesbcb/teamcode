# Native local proxy services

The macOS and Linux native CLI can register a per-user local proxy. Install the binary at a stable absolute path, sign in, and run:

```sh
teamcode login --server https://teamcode.example.org
teamcode service install --port 8789
teamcode service status
teamcode run claude -- --dangerously-skip-permissions
teamcode run codex -- exec "Explain this repository"
```

`service install` registers a macOS LaunchAgent or Linux systemd user unit, starts it now, and enables startup at user login. Linux requires a running systemd user manager. These are user services, without sudo. Startup before login, Windows services and automated binary updates remain planned; they are not implemented by `service install`.

## Lifecycle

- `service print` renders the registration without installing it. It never includes tokens.
- `service install [--port PORT] [--binary ABSOLUTE_PATH]` is idempotent for the existing installation. The default executable is the canonical path of the currently running native CLI. To change the executable path or port, uninstall and reinstall; the team login is preserved.
- `service start`, `service stop` and `service restart` operate only on the registration recorded for the selected configuration. Stopping drains accepted streams for up to ten minutes. A restart waits for the previous process to exit before launching its replacement.
- `service status` reports installation, authenticated local proxy response, central connectivity and team authentication separately. A failed central connection leaves authentication unverified, not necessarily expired.
- `service logs` prints a bounded local log of lifecycle and generic transport diagnostics. It contains at most 128 lines and no credentials, prompts, raw exceptions or response bodies.
- `service uninstall` removes its own registration and service settings, preserving the saved team login and logs. Repeating it is harmless. Externally modified registrations are not overwritten or removed.

On macOS the registration is under `~/Library/LaunchAgents/`; on Linux it is under `$XDG_CONFIG_HOME/systemd/user/` (default `~/.config/systemd/user/`). Each installation has a random identifier so isolated configurations do not control each other's service. A Linux service manager may create enablement links in its own configuration directory.

The login remains in `teamcode/config.json`, and `teamcode/service.json` holds the local capability, port and registration metadata. Both files are private to the user. Service manifests contain only absolute paths and non-secret startup arguments. The service does not need a shell, a working-directory convention or environment variables containing credentials.

## Authentication and recovery

The local proxy binds only to `127.0.0.1` and requires its own random capability. Its identity endpoint also requires that capability and rejects browser origins. `teamcode run` checks the installation ID and configured upstream origin before reusing the service. When no matching service responds, `run` uses an isolated ephemeral proxy as before. It never stops an existing service when the child exits.

The daemon reads the saved team token for every upstream request. Signing in again to the same server takes effect without a service restart; revoked sessions are still rejected centrally. Changing the server URL requires `service restart`, so a token for another origin can never be sent to the old server. If the login file is absent at startup, the daemon waits for it. It does not open a browser or ask for passwords at login/boot.

Upstream outages do not terminate the listener. Later client requests retry connectivity; inference requests are not replayed locally. The service manager retries a failed daemon after 30 seconds. A busy local port is an error and never causes another process to be stopped. No autonomous model work runs in the daemon.

## Verification

`scripts/smoke-service.py --binary /absolute/path/to/teamcode` creates a temporary configuration, synthetic HTTP upstream and uniquely named OS registration. It verifies native installation/idempotence, private files, authenticated discovery, login replacement, offline recovery, `run` reuse, stop during a live stream, restart, registration ownership checks and uninstall. It cleans up only its own registration and processes. No provider account or real team credential is used.

This test exercises the actual OS service manager. It does not reboot the workstation or prove a machine-wide service setup. CI runs it on macOS and Linux.

Platform references: [Apple LaunchAgents](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html), [systemd service execution](https://www.freedesktop.org/software/systemd/man/262/systemd.service.html). Ktor's [Native server startup](https://github.com/ktorio/ktor/blob/3.6.0/ktor-server/ktor-server-core/posix/src/io/ktor/server/engine/EmbeddedServer.posix.kt) installs its own signal callback; TeamCode installs its service handler after startup so shutdown also exits the outer CLI loop.
