#!/usr/bin/env python3
"""Opt-in real Claude acceptance: isolated server, native import/proxy and real Claude Code.

Never run in CI. Uses existing access tokens only, never refreshes, and removes its
own encrypted test database on exit. No source configuration is modified.
"""
import argparse
import base64
import hashlib
import shutil
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--binary', required=True)
parser.add_argument('--jar', required=True)
parser.add_argument('--source', type=Path, required=True, help='Authorized TeamClaude configuration')
parser.add_argument('--model', default='claude-haiku-4-5-20251001')
args = parser.parse_args()
binary, jar = str(Path(args.binary).resolve()), str(Path(args.jar).resolve())
name = 'teamcode-acceptance-' + secrets.token_hex(6)
process = None
proxy = None
container_created = False

def command(arguments, **kwargs):
    try:
        return subprocess.run(arguments, check=True, text=True, capture_output=True, **kwargs)
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
        raise RuntimeError('Acceptance subprocess failed; output withheld to protect credentials.') from None

def stop_own_process():
    global process
    if process is not None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()  # Exact child started by this test only.
            process.wait(timeout=5)
        process = None

with tempfile.TemporaryDirectory(prefix='teamcode-provider-acceptance-') as directory:
    root = Path(directory)
    password = secrets.token_urlsafe(32)
    database_password = secrets.token_urlsafe(32)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    env = dict(os.environ, TEAMCODE_MODE='server', TEAMCODE_PORT=str(port), TEAMCODE_PUBLIC_URL=origin,
               TEAMCODE_DATABASE_USER='teamcode', TEAMCODE_DATABASE_PASSWORD=database_password,
               TEAMCODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               XDG_CONFIG_HOME=str(root / 'config'))
    log = root / 'server.log'
    try:
        command(['docker', 'run', '-d', '--name', name, '--tmpfs', '/var/lib/mysql:rw,nosuid,size=1g', '-p', '127.0.0.1::3306',
                 '-e', 'MYSQL_DATABASE=teamcode', '-e', 'MYSQL_USER=teamcode',
                 '-e', f'MYSQL_PASSWORD={database_password}', '-e', f'MYSQL_ROOT_PASSWORD={database_password}',
                 'mysql:8.4'])
        container_created = True
        db_port = command(['docker', 'port', name, '3306/tcp']).stdout.strip().rsplit(':', 1)[1]
        env['TEAMCODE_DATABASE_URL'] = f'jdbc:mysql://127.0.0.1:{db_port}/teamcode'
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            # Test only our own isolated container, never an existing database.
            check = subprocess.run(['docker', 'exec', name, 'mysqladmin', 'ping', '-h', '127.0.0.1', '--silent'], capture_output=True)
            if check.returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Isolated MySQL did not start')

        def start():
            global process
            with log.open('w') as output:
                process = subprocess.Popen(['java', '-jar', jar], env=env, stdout=output, stderr=subprocess.STDOUT)
            for _ in range(120):
                if process.poll() is not None:
                    raise RuntimeError('TeamCode exited before readiness; inspect the isolated smoke log locally')
                try:
                    with urllib.request.urlopen(origin + '/health', timeout=1) as response:
                        if response.status == 200:
                            return
                except (OSError, urllib.error.URLError):
                    time.sleep(.25)
            raise RuntimeError('TeamCode readiness timed out')

        def request(path, data=None, token=None, opener=None, form=False, csrf_origin=None):
            headers = {}
            body = None
            if data is not None:
                body = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
                headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            if token:
                headers['Authorization'] = 'Bearer ' + token
            if csrf_origin:
                headers['Origin'] = csrf_origin
            req = urllib.request.Request(origin + path, body, headers)
            try:
                with (opener or urllib.request.build_opener()).open(req, timeout=20) as response:
                    return response.status, response.read().decode()
            except urllib.error.HTTPError as error:
                return error.code, error.read().decode()

        start()
        initial = re.search(r'TeamCode temporary administrator password: (\S+)', log.read_text()).group(1)
        assert log.read_text().count('TeamCode temporary administrator password:') == 1
        # Actual native binary performs temporary-password replacement and persists only its session.
        command([binary, 'login', '--server', origin, '--username', 'admin', '--password-stdin'],
                env=env, input=f'{initial}\n{password}\n{password}\n', timeout=30)
        saved = json.loads((root / 'config/teamcode/config.json').read_text())
        token = saved['accessToken']
        assert saved['serverUrl'] == origin
        assert password not in json.dumps(saved) and initial not in json.dumps(saved)
        assert request('/api/v1/members', token=token)[0] == 200
        source_digest = hashlib.sha256(args.source.read_bytes()).hexdigest()
        # Import the complete source, including unsupported/expired entries. Partial failure is explicit.
        imported = subprocess.run([binary, 'accounts', 'import', '--from', 'teamclaude', '--config',
                                   str(args.source.resolve()), '--access-only'], env=env, text=True,
                                  capture_output=True, timeout=180)
        status, raw_accounts = request('/api/v1/accounts', token=token)
        assert status == 200
        first = {a['id']: a for a in json.loads(raw_accounts)}
        assert first and all(a['state'] == 'ACCESS_ONLY' for a in first.values())
        print(f'Native import: {len(first)} verified accounts; partial failure={imported.returncode != 0}.', flush=True)
        repeated = subprocess.run([binary, 'accounts', 'import', '--from', 'teamclaude', '--config',
                                   str(args.source.resolve()), '--access-only'], env=env, text=True,
                                  capture_output=True, timeout=180)
        second = {a['id']: a for a in json.loads(request('/api/v1/accounts', token=token)[1])}
        assert first.keys() == second.keys()
        assert all(second[key]['credentialVersion'] == first[key]['credentialVersion'] + 1 for key in first)
        assert hashlib.sha256(args.source.read_bytes()).hexdigest() == source_digest, 'Source changed during acceptance.'
        print('Repeated native import preserved account IDs and increased credential versions.', flush=True)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            local_port = listener.getsockname()[1]
        local_token = secrets.token_urlsafe(32)
        env['TEAMCODE_LOCAL_TOKEN'] = local_token
        with (root / 'proxy.log').open('w') as output:
            proxy = subprocess.Popen([binary, 'proxy', 'start', '--port', str(local_port)], env=env,
                                     stdout=output, stderr=subprocess.STDOUT)
        for _ in range(100):
            if proxy.poll() is not None:
                raise RuntimeError('Native proxy exited before readiness.')
            try:
                with socket.create_connection(('127.0.0.1', local_port), timeout=.2):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise RuntimeError('Native proxy readiness timed out.')
        # This exact child uses no personal hooks, tools, keychain or conversation history.
        agent_env = {key: value for key, value in env.items()
                     if not key.startswith(('ANTHROPIC_', 'CLAUDE_', 'CLAUDECODE', 'CMUX_'))}
        agent_env.update(ANTHROPIC_BASE_URL=f'http://127.0.0.1:{local_port}',
                         ANTHROPIC_API_KEY=local_token, CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1')
        claude = shutil.which('claude')
        assert claude, 'Claude Code must be installed for this acceptance test.'
        result = subprocess.run([claude, '--bare', '--setting-sources', '', '--strict-mcp-config',
                                 '--tools', '', '--no-session-persistence', '--disable-slash-commands',
                                 '--model', args.model, '--output-format', 'json',
                                 '--system-prompt', 'Reply briefly.', '-p', 'Reply with exactly TEAMCODE_OK.'],
                                env=agent_env, cwd=root, text=True, capture_output=True, timeout=90)
        lines = log.read_text().splitlines()
        events = [line for line in lines if line.startswith('TeamCode inference ')]
        for event in events:
            print(event, flush=True)  # Metadata only; never provider payloads or credentials.
        assert result.returncode == 0, 'Claude Code failed; agent output deliberately withheld.'
        parsed = json.loads(result.stdout)
        assert parsed.get('is_error') is not True and 'TEAMCODE_OK' in parsed.get('result', ''), 'Claude Code did not complete successfully.'
        assert any('provider=anthropic model=' + args.model in line for line in events)
        assert any('status=200' in line for line in events)
        assert any('settled' in line and 'units=unknown' not in line for line in events)
        assert hashlib.sha256(args.source.read_bytes()).hexdigest() == source_digest
        print('Real acceptance passed: native import/upsert -> real Claude Code -> native proxy -> TeamCode -> Claude Haiku; usage settled.', flush=True)
    finally:
        if proxy is not None:
            proxy.terminate()
            try:
                proxy.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proxy.kill()  # Exact child owned by this test only.
                proxy.wait(timeout=5)
        stop_own_process()
        if container_created:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True, check=True)
