# Native local proxy services

The native CLI can register a per-user local proxy. Native runtime acceptance has passed on macOS, Linux and Windows, including Task Scheduler installation, reuse, draining, restart and uninstall. Install the binary at a stable absolute path, sign in, and run:

```sh
teamcode login --server https://teamcode.example.org
teamcode service install --port 8789
teamcode service status
teamcode run claude -- --dangerously-skip-permissions
teamcode run codex -- exec "Explain this repository"
```

`service install` registers a macOS LaunchAgent, Linux systemd user unit or Windows scheduled logon task, starts it now, and enables startup at user login. Linux requires a running systemd user manager. These are user services; the Windows task uses the current user's interactive token at least privilege and stores no Windows password. Startup before login remains planned. Native binary updates are handled separately by the managed installer/updater below.

## Lifecycle

- `service print` renders the registration without installing it. It never includes tokens.
- `service install [--port PORT] [--binary ABSOLUTE_PATH]` is idempotent for the existing installation. The default executable is the immutable launcher for a managed installation, or the canonical running native executable for a manual installation. To change the executable path or port, uninstall and reinstall; the team login is preserved.
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

## Native installation and updates

`teamcode install [--version VERSION | --bundle DIRECTORY] [--root ABSOLUTE_PATH]` creates a private managed installation. Add its printed `bin` directory to PATH, then use `teamcode service install` through that launcher. Existing login/device identity is preserved. A manual/source service registered to a different executable is left intact; uninstall/reinstall that service through the managed launcher to change its executable safely.

`teamcode update --check [--version VERSION]` authenticates published metadata without reading team authentication or touching the service. `teamcode update`, `update --version VERSION`, offline `update --bundle DIRECTORY` and `update --rollback` operate on a managed installation. New payloads pass publisher signature, exact hash/length and native version/API compatibility checks before activation. A configured server must be reachable for an installation/update compatibility check; a previously accepted rollback remains available offline. See [distribution behavior and remaining acceptance](distribution.md).

The immutable launcher selects a private versioned payload, so Windows never replaces a running executable. A writer lock serializes updater processes; a separate activity lock protects every managed `teamcode run` for its entire lifetime. Downloads can stage during agent work, but activation refuses while a quiet or busy managed agent holds its lease. No agent is killed. Previous payloads are retained.

An update cooperatively drains/stops a matching running service, switches the selected payload atomically, starts through the same registered launcher and checks the authenticated local identity/version. Stopped services stay stopped. Failed startup restores the previous selection and restarts the previous service where possible. A durable before/after journal supports recovery after updater interruption; a recovery failure retains the journal rather than claiming success. Service registration, configuration and credentials are preserved.

Local real acceptance covers macOS ARM64 installation, two compiled versions, service restart, active quiet-agent refusal, offline rollback and interrupted selection. The unchanged manual-service lifecycle smoke also passes. Complete five-target signed installer/update acceptance, clean workstation reboot/logon and pre-login service support remain required. Managed services now check signed release metadata in the background with a shared 24-hour cache; use `teamcode update --notifications off` to disable this installation's notifications. Checks never install code. Opt-in unattended installation remains planned; an explicit update is currently required. See [notification behavior](distribution.md#background-release-notifications).

Server upgrades use Helm rolling deployments with readiness and stream draining. CI publication alone does not restart Kubernetes pods. The chart keeps `latest` as its default, with immutable image tags available for explicit upgrades and rollback. Prefer additive database migrations that remain compatible with the preceding server release; document any exception before release. Application rollback must never automatically undo a database migration destructively. Restore testing and mixed-version rollout acceptance are still required.
