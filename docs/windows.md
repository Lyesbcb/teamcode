# Windows workstations

Windows x64 is a V1 target. Native runtime acceptance is still in progress; check [implementation status](status.md) before rolling it out to a teammate. TeamCode runs directly on Windows, without WSL, Java or Gradle at runtime.

## Install a tested development build

Open a successful [Build workflow run](https://github.com/nathanfallet/teamcode/actions/workflows/build.yml) and download its `teamcode-windows-x64` artifact. It is uploaded only after that run's Windows acceptance test passes. Extract `teamcode.exe` and keep it at a stable location, for example `%LOCALAPPDATA%\TeamCode\bin\teamcode.exe`. These are development builds; versioned releases and automatic updates are still planned.

In a regular PowerShell session:

```powershell
$teamcode = Join-Path $env:LOCALAPPDATA 'TeamCode\bin\teamcode.exe'
& $teamcode login --server https://teamcode.example.org
& $teamcode status
& $teamcode run claude -- --dangerously-skip-permissions
& $teamcode run codex -- exec "Explain this repository"
```

Use the username and temporary password created by your TeamCode administrator. The first login asks you to replace the temporary password. TeamCode saves the server URL and session in `%USERPROFILE%\.config\teamcode\config.json` by default; `HOME`, when set, takes precedence over `USERPROFILE`, and `XDG_CONFIG_HOME` overrides the configuration root. Configuration files have a protected NTFS access-control list for the current user.

Install Claude Code or Codex separately and make the chosen command available on `PATH`. TeamCode supports native `.exe` installations and the standard npm package layouts. For npm installations it launches the package's JavaScript entry point with Node.js; Node must also be available. Arguments following `--` are passed to the agent literally. TeamCode does not rewrite the agent's personal configuration.

To use the shorter `teamcode` command, add the executable's directory to your **user** `Path` in Windows Environment Variables and open a new terminal.

## Start automatically at login

```powershell
& $teamcode service install
& $teamcode service status
& $teamcode service logs
```

This registers a task for the current user's login and starts the authenticated local proxy immediately. It stores no Windows password. `teamcode run` reuses that proxy after verifying its identity. Keep the executable at its registered location.

```powershell
& $teamcode service stop
& $teamcode service start
& $teamcode service restart
& $teamcode service uninstall
```

Stopping disables automatic login startup and waits for accepted streams to finish; starting enables it again. Uninstall preserves your TeamCode login. A service running before anyone signs in is a separate planned feature. See [service lifecycle and recovery](services.md) for the full behavior.

## Build and test from source

Development requires JDK 21. From the repository in PowerShell:

```powershell
.\gradlew.bat :cli:linkDebugExecutableMingwX64
python scripts/smoke-windows.py --binary cli/build/bin/mingwX64/debugExecutable/teamcode.exe
```

The acceptance script also requires Node.js and PowerShell 7 (`pwsh`). It uses synthetic credentials, a temporary configuration, an isolated test certificate and its own scheduled task. It verifies native authentication, HTTPS trust, file permissions, streaming, agent process handling and the service lifecycle without calling a model provider. The test removes its own certificate and task afterward.
