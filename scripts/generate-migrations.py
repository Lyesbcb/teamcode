#!/usr/bin/env python3
"""Generate Exposed migration candidates against an owned, disposable MySQL database."""
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
PASSWORD = "teamcode-migration-fixture"  # Synthetic credential for this disposable container only.


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def main():
    container = run(
        "docker", "run", "--rm", "--detach", "--tmpfs", "/var/lib/mysql:rw,nosuid,size=1g",
        "-e", f"MYSQL_ROOT_PASSWORD={PASSWORD}", "-e", "MYSQL_DATABASE=teamcode",
        "-p", "127.0.0.1::3306", "mysql:8.4", capture_output=True,
    ).stdout.strip()
    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            probe = subprocess.run(
                ["docker", "exec", "-e", f"MYSQL_PWD={PASSWORD}", container, "mysqladmin", "--protocol=tcp", "-h", "127.0.0.1", "-u", "root", "ping"],
                capture_output=True, text=True, timeout=10,
            )
            if probe.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("The disposable migration database did not become ready in 180 seconds.")
        endpoint = run("docker", "port", container, "3306/tcp", capture_output=True, timeout=15).stdout.strip()
        host, port = endpoint.rsplit(":", 1)
        if host != "127.0.0.1" or not port.isdecimal():
            raise RuntimeError("The migration database must be bound only to loopback.")
        run(str(ROOT / "gradlew"), ":infrastructure:generateMigrations", f"-PteamcodeMigrationPort={port}", cwd=ROOT)
    finally:
        # Only the exact container created by this invocation is stopped.
        subprocess.run(["docker", "stop", container], check=False, stdout=subprocess.DEVNULL, timeout=30)


if __name__ == "__main__":
    main()
