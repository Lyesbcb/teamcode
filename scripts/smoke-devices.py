#!/usr/bin/env python3
"""Actual native installation identity and metadata commands against an isolated fake team API.

Real MySQL/authorization and quota invariants are covered separately by DevicePersistenceTest
and smoke-team.py. This fixture never contacts a provider or uses personal configuration.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import tempfile
import threading
import time
import traceback
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    binary = str(parser.parse_args().binary.resolve())
    password = secrets.token_urlsafe(32)
    member = str(uuid.uuid4())
    device = str(uuid.uuid4())
    legacy_id = str(uuid.uuid4())
    installation_ids = []
    tokens = {}
    issued_tokens = []
    device_name = 'Fixture workstation'
    legacy_active = True
    errors = []
    lock = threading.RLock()
    now = int(time.time() * 1000)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def send_json(self, data, status=200):
            body = json.dumps(data).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def authorize(self):
            self.token = self.headers.get('Authorization', '').removeprefix('Bearer ')
            if self.token not in tokens:
                self.send_json({'error': {'code': 'authentication_required'}}, 401)
                return False
            return True

        def do_POST(self):
            with lock:
                if self.path == '/api/v1/auth/login':
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    assert body['username'] == 'fixture' and body['password'] == password, 'Invalid synthetic login'
                    assert str(uuid.UUID(body['installationId'])) == body['installationId'], 'Invalid installation UUID'
                    installation_ids.append(body['installationId'])
                    token = secrets.token_urlsafe(32)
                    issued_tokens.append(token)
                    tokens[token] = str(uuid.uuid4())
                    self.send_json({'accessToken': token, 'expiresAt': now + 3_600_000, 'member': {
                        'id': member, 'username': 'fixture', 'role': 'MEMBER', 'disabled': False, 'passwordChangeRequired': False}})
                elif self.path == '/api/v1/auth/logout':
                    if not self.authorize():
                        return
                    del tokens[self.token]
                    self.send_json({'success': True})
                else:
                    self.send_error(404)

        def do_GET(self):
            with lock:
                if not self.authorize():
                    return
                if self.path == '/api/v1/auth/me':
                    self.send_json({'id': member, 'username': 'fixture', 'role': 'MEMBER', 'disabled': False, 'passwordChangeRequired': False})
                elif self.path == '/api/v1/devices':
                    self.send_json([{'id': device, 'memberId': member, 'name': device_name, 'createdAt': now,
                                     'lastLoginAt': now, 'activeSessions': len(tokens), 'current': True}])
                elif self.path == '/api/v1/auth/sessions':
                    rows = [{'id': value, 'memberId': member, 'name': device_name, 'deviceId': device, 'createdAt': now,
                             'expiresAt': now + 3_600_000, 'current': key == self.token} for key, value in tokens.items()]
                    if legacy_active:
                        rows.append({'id': legacy_id, 'memberId': member, 'name': 'Legacy browser', 'deviceId': None,
                                     'createdAt': None, 'expiresAt': now + 3_600_000, 'current': False})
                    self.send_json(rows)
                elif self.path.startswith('/api/v1/members/'):
                    self.send_json({'error': {'code': 'forbidden'}}, 403)
                else:
                    self.send_error(404)

        def do_PATCH(self):
            nonlocal device_name
            with lock:
                if not self.authorize():
                    return
                assert self.path == '/api/v1/devices/' + device
                device_name = json.loads(self.rfile.read(int(self.headers['Content-Length'])))['name']
                self.send_json({'id': device, 'memberId': member, 'name': device_name, 'createdAt': now,
                                'lastLoginAt': now, 'activeSessions': len(tokens), 'current': True})

        def do_DELETE(self):
            nonlocal legacy_active
            with lock:
                if not self.authorize():
                    return
                if self.path == '/api/v1/auth/sessions/' + legacy_id:
                    legacy_active = False
                else:
                    assert self.path == '/api/v1/devices/' + device
                    tokens.clear()
                self.send_response(204)
                self.send_header('Content-Length', '0')
                self.end_headers()

    class Server(ThreadingHTTPServer):
        def handle_error(self, *_):
            errors.append(traceback.format_exc())

    server = Server(('127.0.0.1', 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with tempfile.TemporaryDirectory(prefix='teamcode-device-smoke-') as directory:
            env = dict(os.environ, XDG_CONFIG_HOME=directory)
            origin = f'http://127.0.0.1:{server.server_port}'

            def command(*arguments, stdin=None, check=True):
                result = subprocess.run([binary, *arguments], env=env, input=stdin, text=True, encoding='utf-8',
                                        capture_output=True, timeout=30)
                if check:
                    assert result.returncode == 0, 'Native identity command failed: ' + result.stderr
                with lock:
                    private_values = [password, *issued_tokens]
                for secret in private_values:
                    assert secret not in result.stdout + result.stderr, 'CLI exposed a synthetic credential'
                return result

            def login(_=None):
                command('login', '--server', origin, '--username', 'fixture', '--password-stdin',
                        '--device', 'Fixture workstation', stdin=password + '\n')

            with ThreadPoolExecutor(max_workers=4) as pool:
                list(pool.map(login, range(4)))
            identity = Path(directory) / 'teamcode/installation-id'
            assert len(installation_ids) == 4 and len(set(installation_ids)) == 1, 'Concurrent first logins changed installation identity'
            assert identity.read_text() == installation_ids[0]
            if os.name == 'posix':
                assert stat.S_IMODE(identity.stat().st_mode) == 0o600
                assert stat.S_IMODE(identity.parent.stat().st_mode) == 0o700
            else:
                acl = r'''
                $ErrorActionPreference='Stop'
                $acl=Get-Acl -LiteralPath $env:TEAMCODE_TEST_IDENTITY
                $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
                $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
                if (!$acl.AreAccessRulesProtected -or $rules.Count -ne 1 -or
                    $rules[0].IdentityReference.Value -ne $owner -or $rules[0].IsInherited -or
                    $rules[0].AccessControlType -ne 'Allow' -or
                    ($rules[0].FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne [Security.AccessControl.FileSystemRights]::FullControl) {
                    throw 'Installation identity is not protected by an owner-only DACL'
                }
                '''
                subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', acl],
                               env=dict(env, TEAMCODE_TEST_IDENTITY=str(identity)), check=True, capture_output=True, timeout=15)
            assert 'fixture (member)' in command('whoami').stdout
            assert device in command('devices', 'list').stdout
            sessions = command('auth', 'sessions', 'list').stdout
            assert legacy_id in sessions and 'current' in sessions
            command('auth', 'sessions', 'revoke', legacy_id)
            assert legacy_id not in command('auth', 'sessions', 'list').stdout
            label = "Developer's café workstation"
            command('devices', 'rename', device, '--name', label)
            assert label in command('devices', 'list').stdout
            assert command('devices', 'list', '--member', str(uuid.uuid4()), check=False).returncode != 0
            command('logout')
            assert not (identity.parent / 'config.json').exists()
            assert identity.read_text() == installation_ids[0]
            login()
            assert len(set(installation_ids)) == 1
            assert label in command('devices', 'list').stdout
            command('devices', 'revoke', device)
            assert command('whoami', check=False).returncode != 0
            assert not errors, '\n'.join(errors)
            assert {p.name for p in identity.parent.iterdir()} == {'installation-id', 'config.json'}, 'Temporary file leaked'
            print('Native devices passed: concurrent private identity creation, login/logout persistence, whoami, typed lists, rename and revocation.')
    finally:
        server.shutdown(); server.server_close(); worker.join(timeout=5)


if __name__ == '__main__':
    main()
