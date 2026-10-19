# Native local proxy services

The native CLI can register a per-user local proxy. Native runtime acceptance has passed on macOS, Linux and Windows, including Task Scheduler installation, reuse, draining, restart and uninstall. Install the binary at a stable absolute path, sign in, and run:

```sh
teamcode login --server https://teamcode.example.org
teamcode service install --port 8789
teamcode service status
teamcode run claude -- --dangerously-skip-permissions
teamcode run codex -- exec "Explain this repository"
```

`service install` registers a macOS LaunchAgent, Linux systemd user unit or Windows scheduled logon task, starts it now, and enables startup at user login. Linux requires a running systemd user manager. These are user services; the Windows task uses the current user's interactive token at least privilege and stores no Windows password. Startup before login and automated binary updates remain planned; they are not implemented by `service install`.

## Lifecycle

- `service print` renders the registration without installing it. It never includes tokens.
- `service install [--port PORT] [--binary ABSOLUTE_PATH]` is idempotent for the existing installation. The default executable is the canonical path of the currently running native CLI. To change the executable path or port, uninstall and reinstall; the team login is preserved.
- `service start`, `service stop` and `service restart` operate only on the registration recorded for the selected configuration. Stopping drains accepted streams for up to ten minutes. A restart waits for the previous process to exit before launching its replacement.
- `service status` reports installation, authenticated local proxy response, central connectivity and team authentication separately. A failed central connection leaves authentication unverified, not necessarily expired.
- `service logs` prints a bounded local log of lifecycle and generic transport diagnostics. It contains at most 128 lines and no credentials, prompts, raw exceptions or response bodies.
- `service uninstall` removes its own registration and service settings, preserving the saved team login and logs. Repeating it is harmless. Externally modified registrations are not overwritten or removed.

On macOS the registration is under `~/Library/LaunchAgents/`; on Linux it is under `$XDG_CONFIG_HOME/systemd/user/` (default `~/.config/systemd/user/`). Windows keeps its Task Scheduler XML beside the private service settings. Each installation has a random identifier so isolated configurations do not control each other's service. A Linux service manager may create enablement links in its own configuration directory.

On Windows, `service stop` disables the scheduled task and writes a private stop request. The daemon drains accepted streams and exits; the CLI waits until Task Scheduler reports no running instances. `service start` clears that request and re-enables both immediate execution and automatic logon startup. It does not use Task Scheduler's forceful end operation. Use TeamCode's start/stop commands so the cooperative stop state stays consistent.

The login remains in `teamcode/config.json`, and `teamcode/service.json` holds the local capability, port and registration metadata. Both files are private to the user: POSIX 0600 files/0700 directories, or a protected Windows DACL with only the current user granted access. Service manifests contain only absolute paths and non-secret startup arguments. The daemon does not need a shell, a working-directory convention or environment variables containing credentials. Windows management uses built-in Task Scheduler tools and a fixed PowerShell COM query for running instances; it never evaluates agent arguments as shell code.

## Authentication and recovery

The local proxy binds only to `127.0.0.1` and requires its own random capability. Its identity endpoint also requires that capability and rejects browser origins. `teamcode run` checks the installation ID and configured upstream origin before reusing the service. When no matching service responds, `run` uses an isolated ephemeral proxy as before. It never stops an existing service when the child exits.

The daemon reads the saved team token for every upstream request. Signing in again to the same server takes effect without a service restart; revoked sessions are still rejected centrally. Changing the server URL requires `service restart`, so a token for another origin can never be sent to the old server. If the login file is absent at startup, the daemon waits for it. It does not open a browser or ask for passwords at login/boot.

Upstream outages do not terminate the listener. Later client requests retry connectivity; inference requests are not replayed locally. The service manager retries a failed daemon after 30 seconds on macOS/Linux, or once per minute for at most 255 attempts on Windows. A busy local port is an error and never causes another process to be stopped. No autonomous model work runs in the daemon.

## Verification

Each `run` invocation can select `--workload background` before the agent argument delimiter. The service keeps both classes available without a global mode switch: only that launch uses the background inference path. Discovery advertises `workload-routing-v1`; a background launch reuses the service only when the authenticated installation ID, server URL and this capability match. Otherwise it uses its own ephemeral proxy and leaves the service intact. Interactive launches remain compatible with older service discovery responses.

`scripts/smoke-service.py --binary /absolute/path/to/teamcode` creates a temporary configuration, synthetic HTTP upstream and uniquely named OS registration. It verifies native installation/idempotence, private files, authenticated discovery, login replacement, offline recovery, `run` reuse, stop during a live stream, restart, registration ownership checks and uninstall. It cleans up only its own registration and processes. No provider account or real team credential is used.

This test exercises the actual OS service manager. It does not reboot the workstation or prove a machine-wide service setup. CI runs it on macOS and Linux.

`scripts/smoke-windows.py` separately runs the Windows executable, inspects real NTFS ACLs, checks Unicode paths/arguments and standard npm agent entry points, then exercises Task Scheduler installation, reuse, draining, restart and cleanup. Windows support is required for V1; its current evidence is recorded in [status](status.md).

Platform references: [Apple LaunchAgents](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html), [systemd service execution](https://www.freedesktop.org/software/systemd/man/262/systemd.service.html). Ktor's [Native server startup](https://github.com/ktorio/ktor/blob/3.6.0/ktor-server/ktor-server-core/posix/src/io/ktor/server/engine/EmbeddedServer.posix.kt) installs its own signal callback; TeamCode installs its service handler after startup so shutdown also exits the outer CLI loop.

Windows references: [Task Scheduler logon triggers](https://learn.microsoft.com/en-us/windows/win32/taskschd/logon-trigger-example--xml-), [CreateProcess Unicode environments](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw), [system-preferred random generator](https://learn.microsoft.com/en-us/windows/win32/api/bcrypt/nf-bcrypt-bcryptgenrandom).

The built-in Windows PowerShell service query uses system module paths explicitly, avoiding incompatible PowerShell 7 modules inherited through the caller. See Microsoft's [PSModulePath behavior for intermediate processes](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_psmodulepath).

## Planned versioned updates (not implemented)

Native release artifacts will be published through GitHub Releases after the required platform checks. A signed manifest will bind each platform artifact to its version and digest; the updater must verify it before executing downloaded code. The release signing key must be separate from the self-hosted server and verified against a trust root shipped with the CLI. Release signing, key rotation and verification require acceptance tests.

Planned commands are `teamcode update --check`, `teamcode update`, `teamcode update --version VERSION` and `teamcode update --rollback`. These commands are design targets, not available commands. Automatic checks may notify users; unattended installation is opt-in and waits until the local proxy is idle. A failed check never prevents normal agent launch.

A stable launcher will select an installed version from private, versioned application-data directories, separate from configuration and credentials. On Windows this avoids replacing a running executable. Installation must validate the complete new version before atomically switching the selected version, keep the previous working version and serialize competing updater processes. The URL, session token and provider enrollment configuration remain unchanged.

For a managed local proxy, update stages the binary first, drains accepted streams using the existing cooperative lifecycle, waits for the old process to exit, switches versions and starts the replacement. It must not kill unrelated processes, terminate a running agent, force an update through a busy proxy or delete files still used by a process. Failed startup restores the previous selection and service registration. Acceptance must cover interrupted downloads, invalid signatures, offline operation, Unicode paths, file locks, drain timeouts and recovery on all supported OSes.

The server will expose version/capability information so the CLI can explain incompatible features and avoid installing a version that cannot talk to its configured server. Existing `/api/v1` compatibility remains a release requirement; capability negotiation is still pending implementation.

Server upgrades use Helm rolling deployments with readiness and stream draining. CI publication alone does not restart Kubernetes pods. The chart keeps `latest` as its default, with immutable image tags available for explicit upgrades and rollback. Prefer additive database migrations that remain compatible with the preceding server release; document any exception before release. Application rollback must never automatically undo a database migration destructively. Restore testing and mixed-version rollout acceptance are still required.
