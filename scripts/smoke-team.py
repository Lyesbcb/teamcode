#!/usr/bin/env python3
"""Exercise first-run admin, browser forms, native CLI and persistent sessions using an isolated MySQL."""
import argparse
import base64
import http.cookiejar
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
args = parser.parse_args()
binary, jar = str(Path(args.binary).resolve()), str(Path(args.jar).resolve())
name = 'teamcode-smoke-' + secrets.token_hex(6)
process = None
container_created = False

def command(arguments, **kwargs):
    try:
        return subprocess.run(arguments, check=True, text=True, capture_output=True, **kwargs)
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
        # This only reports CLI diagnostics; its secrets are read from stdin and never echoed.
        if arguments[0] == binary:
            print('Native CLI stdout:', getattr(error, 'stdout', None))
            print('Native CLI stderr:', getattr(error, 'stderr', None))
        raise

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

with tempfile.TemporaryDirectory(prefix='teamcode-team-smoke-') as directory:
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
        command(['docker', 'run', '-d', '--name', name, '-p', '127.0.0.1::3306',
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
        cookies = http.cookiejar.CookieJar()
        browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        assert request('/dashboard/login', {'username': 'admin', 'password': password}, opener=browser, form=True, csrf_origin=origin)[0] == 200
        status, html = request('/dashboard/members', opener=browser)
        assert status == 200
        csrf = re.search(r"name='csrf' value='([^']+)'", html).group(1)
        status, html = request('/dashboard/members', {'csrf': csrf, 'username': 'alice', 'role': 'MEMBER'}, opener=browser, form=True, csrf_origin=origin)
        assert status == 200 and 'Temporary password' in html
        temporary = re.search(r'<code>([^<]+)</code>', html).group(1)
        status, login = request('/api/v1/auth/login', {'username': 'alice', 'password': temporary})
        assert status == 200
        member_token = json.loads(login)['accessToken']
        assert request('/api/v1/status', token=member_token)[0] == 403
        assert request('/api/v1/members', token=member_token)[0] == 403
        assert request('/api/v1/register', {})[0] == 401
        assert request('/dashboard/accounts', opener=browser)[0] == 200
        stop_own_process()
        start()
        assert 'temporary administrator password' not in log.read_text()
        assert request('/api/v1/members', token=token)[0] == 200
        command([binary, 'status'], env=env, timeout=15)
        command([binary, 'logout'], env=env, timeout=15)
        assert not (root / 'config/teamcode/config.json').exists()
        assert request('/api/v1/members', token=token)[0] == 401
        assert password not in log.read_text() and temporary not in log.read_text()
        print('Team smoke passed: one-time bootstrap, forced password change, browser member creation, native login/logout, restart persistence and revocation.')
    finally:
        stop_own_process()
        if container_created:
            subprocess.run(['docker', 'rm', '-f', name], capture_output=True, check=True)
