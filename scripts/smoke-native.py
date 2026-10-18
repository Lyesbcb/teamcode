#!/usr/bin/env python3
"""Run the real native proxy against the JVM fixture; never contact a provider.

Usage: python3 scripts/smoke-native.py --binary path/to/teamcode.kexe --jar path/to/app-all.jar
Only subprocesses created by this script are terminated. Uses an isolated XDG directory.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait_ready(url, process, token=None):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Owned test process exited before readiness.')
        try:
            headers = {'Authorization': f'Bearer {token}'} if token else {}
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=1) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code in (401, 404, 405):
                return b''
        except (OSError, TimeoutError):
            pass
        time.sleep(.1)
    raise RuntimeError('Owned test process did not become ready.')


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--binary', type=Path, required=True)
    args.add_argument('--jar', type=Path, required=True)
    options = args.parse_args()
    server_port, local_port = port(), port()
    token, local_token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    processes = []
    with tempfile.TemporaryDirectory(prefix='teamcode-smoke-') as directory:
        env = {**os.environ, 'XDG_CONFIG_HOME': directory, 'TEAMCODE_MODE': 'fixture',
               'TEAMCODE_ACCESS_TOKEN': token, 'TEAMCODE_LOCAL_TOKEN': local_token,
               'TEAMCODE_PORT': str(server_port), 'TEAMCODE_HOST': '127.0.0.1'}
        origin = f'http://127.0.0.1:{server_port}'
        binary = str(options.binary.resolve())
        with open(Path(directory) / 'server.log', 'w') as server_log, open(Path(directory) / 'proxy.log', 'w') as proxy_log:
            try:
                server = subprocess.Popen(['java', '-jar', str(options.jar.resolve())], env=env, stdout=server_log, stderr=server_log)
                processes.append(server)
                wait_ready(origin + '/health', server)
                subprocess.run([binary, 'configure', '--server', origin], input=token + '\n', text=True,
                               env=env, check=True, timeout=30, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                config_path = Path(directory) / 'teamcode/config.json'
                config = json.loads(config_path.read_text())
                assert config['serverUrl'] == origin and config['accessToken'] == token
                if os.name == 'posix':
                    assert stat.S_IMODE(config_path.stat().st_mode) == 0o600
                    assert stat.S_IMODE(config_path.parent.stat().st_mode) == 0o700
                result = subprocess.run([binary, 'status'], env=env, check=True, text=True, capture_output=True, timeout=30)
                assert 'Mode: fixture' in result.stdout and token not in result.stdout
                proxy = subprocess.Popen([binary, 'proxy', 'start', '--port', str(local_port)], env=env, stdout=proxy_log, stderr=proxy_log)
                processes.append(proxy)
                url = f'http://127.0.0.1:{local_port}/v1/messages'
                wait_ready(url, proxy)
                payload = json.dumps({'model': 'teamcode-fixture', 'messages': [], 'stream': True}).encode()
                unauthenticated = urllib.request.Request(url, data=payload, headers={'Content-Type': 'application/json'})
                try:
                    urllib.request.urlopen(unauthenticated, timeout=5)
                    raise AssertionError('Local proxy accepted an unauthenticated request.')
                except urllib.error.HTTPError as error:
                    assert error.code == 401
                request = urllib.request.Request(url, data=payload, headers={
                    'Content-Type': 'application/json', 'x-api-key': local_token})
                with urllib.request.urlopen(request, timeout=10) as response:
                    first = response.readline()
                    first_at = time.monotonic()
                    assert first == b'event: message_start\n'
                    remaining = response.read()
                    assert time.monotonic() - first_at >= .03, 'Stream appears buffered until completion.'
                    assert b'message_stop' in remaining
                    assert b'No provider was called.' in remaining
                    assert token.encode() not in first + remaining
                print('Native configuration, private permissions, authenticated SDK/proxy and progressive SSE verified.')
            finally:
                for process in reversed(processes):
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()  # Exact child owned by this test, never an unrelated process.
                            process.wait(timeout=5)


if __name__ == '__main__':
    main()
