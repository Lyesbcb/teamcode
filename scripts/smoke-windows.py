#!/usr/bin/env python3
"""Run the Windows native CLI with synthetic authentication, ACL inspection and real child processes."""
import argparse
import concurrent.futures
import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--binary', type=Path, required=True)
args = parser.parse_args()
assert os.name == 'nt', 'This acceptance test must run on Windows'
binary = str(args.binary.resolve())
node = Path(shutil.which('node')).resolve()
team_token = secrets.token_hex(32)
local_token = secrets.token_hex(32)
calls = []
checks = []
stream_started = threading.Event()
stream_release = threading.Event()
stream_release.set()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        checks.append(self.path)
        if self.headers.get('Authorization') != 'Bearer ' + team_token:
            self.send_error(401)
            return
        payload = ({'id': 'member', 'username': 'synthetic', 'role': 'MEMBER', 'disabled': False, 'passwordChangeRequired': False}
                   if self.path.endswith('/me') else {'mode': 'fixture', 'activeRequests': 0, 'completedRequests': len(calls)}
                   if self.path.endswith('/status') else {'serverVersion': 'test', 'mode': 'fixture', 'features': []})
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        assert self.headers.get('Authorization') == 'Bearer ' + team_token
        assert self.headers.get('x-api-key') is None
        assert self.path in ('/v1/messages', '/v1/responses')
        assert json.loads(body)['model'] == 'fixture'
        calls.append(self.path)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        self.wfile.write(b'data: {"start":true}\n\n')
        self.wfile.flush()
        stream_started.set()
        assert stream_release.wait(30), 'Test did not release the active stream'
        time.sleep(.4)
        self.wfile.write(b'data: {"done":true}\n\n')
        self.wfile.flush()

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
origin = f'http://127.0.0.1:{server.server_port}'
proxy = None
service_env = None
service_installed = False
directory = tempfile.mkdtemp(prefix='teamcode-windows-')
try:
    with contextlib.nullcontext(directory):
        root = Path(directory) / 'Unicode équipe with spaces'
        root.mkdir()
        env = dict(os.environ, XDG_CONFIG_HOME=str(root / 'configuration'), TEAMCODE_LOCAL_TOKEN=local_token)
        config = Path(env['XDG_CONFIG_HOME']) / 'teamcode/config.json'

        def command(*arguments, stdin=None, check=True):
            result = subprocess.run([binary, *arguments], env=env, input=stdin, text=True,
                                    encoding='utf-8', capture_output=True, timeout=45)
            assert team_token not in result.stdout + result.stderr and local_token not in result.stdout + result.stderr
            if check:
                assert result.returncode == 0, f'{arguments} (upstream checks: {len(checks)}): {result.stdout}\n{result.stderr}'
            return result

        command('configure', '--server', origin, stdin=team_token + '\n')
        original_config = config.read_bytes()
        assert json.loads(original_config)['accessToken'] == team_token
        command('configure', '--server', origin, stdin=team_token + '\n')
        assert config.read_bytes() == original_config, 'Atomic replacement changed the saved values'
        assert 'Mode: fixture' in command('status').stdout
        acl_script = r'''
        $ErrorActionPreference = 'Stop'
        $acl = Get-Acl -LiteralPath $env:TEAMCODE_ACL_PATH
        [pscustomobject]@{
          user = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
          owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
          protected = $acl.AreAccessRulesProtected
          rules = @($acl.Access | ForEach-Object {
            [pscustomobject]@{
              sid = $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
              inherited = $_.IsInherited
              type = $_.AccessControlType.ToString()
              full = ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl
            }
          })
        } | ConvertTo-Json -Depth 4 -Compress
        '''
        for path in (config, config.parent):
            result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', acl_script],
                                    env=dict(env, TEAMCODE_ACL_PATH=str(path)), capture_output=True, text=True, check=True)
            acl = json.loads(result.stdout)
            assert acl['owner'] == acl['user'] and acl['protected'], 'Ownership or protected DACL is incorrect'
            assert len(acl['rules']) == 1, 'Unexpected users/groups can access the private configuration'
            assert acl['rules'][0] == {'sid': acl['user'], 'inherited': False, 'type': 'Allow', 'full': True}

        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        with (root / 'proxy.log').open('w', encoding='utf-8') as log:
            proxy = subprocess.Popen([binary, 'proxy', 'start', '--port', str(port)], env=env, stdout=log, stderr=log)
        for _ in range(100):
            assert proxy.poll() is None, 'Native proxy exited'
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=.2):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise AssertionError('Native proxy did not start')
        request = urllib.request.Request(f'http://127.0.0.1:{port}/v1/messages', b'{"model":"fixture"}',
                                         {'Content-Type': 'application/json', 'x-api-key': local_token})
        with urllib.request.urlopen(request, timeout=10) as response:
            before = time.monotonic()
            assert b'"start":true' in response.readline()
            assert b'"done":true' in response.read()
            assert time.monotonic() - before >= .25, 'SSE was buffered instead of streamed'
        try:
            urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}/v1/messages', b'{}',
                {'Content-Type': 'application/json'}), timeout=5)
            raise AssertionError('Local proxy accepted an unauthenticated request')
        except urllib.error.HTTPError as error:
            assert error.code == 401
        proxy.terminate()
        proxy.wait(timeout=10)
        proxy = None
        log_text = (root / 'proxy.log').read_text(encoding='utf-8')
        assert team_token not in log_text and local_token not in log_text

        agent_source = r'''
        const fs = require('fs'); const http = require('http'); const assert = require('assert');
        (async () => {
          const name = process.env.TEAMCODE_TEST_AGENT;
          let args = process.argv.slice(2), url, token;
          if (name === 'claude') {
            assert(!process.env.ANTHROPIC_AUTH_TOKEN && !process.env.CLAUDE_CODE_OAUTH_TOKEN);
            url = process.env.ANTHROPIC_BASE_URL + '/v1/messages'; token = process.env.ANTHROPIC_API_KEY;
          } else {
            let end = args.indexOf('--'); if (end < 0) end = args.length;
            assert.deepStrictEqual(args.slice(end-4, end-2), ['-c', 'model_provider="teamcode"']);
            url = /base_url="([^"]+)"/.exec(args[end-1])[1] + '/responses';
            token = process.env.TEAMCODE_LOCAL_TOKEN;
            args = args.slice(0, end-4).concat(args.slice(end));
          }
          assert.strictEqual(fs.readFileSync(0, 'utf8'), 'stdin reaches the child\n');
          assert.strictEqual(token.length, 64);
          const body = JSON.stringify({model:'fixture'});
          const reply = await new Promise((resolve, reject) => {
            const request = http.request(url, {method:'POST', headers:{'Content-Type':'application/json',
                'Content-Length':Buffer.byteLength(body), 'Authorization':'Bearer '+token}}, response => {
              assert.strictEqual(response.statusCode, 200); let text='';
              response.on('data', chunk => text += chunk); response.on('end', () => resolve(text));
            }); request.on('error', reject); request.end(body);
          });
          assert(reply.includes('"done":true'));
          fs.writeFileSync(process.env.TEAMCODE_RUN_RECORD, JSON.stringify({args, cwd:process.cwd(), origin:new URL(url).origin}));
          console.log('CHILD_STDOUT_OK'); console.error('CHILD_STDERR_OK'); process.exit(37);
        })().catch(() => { console.error('Synthetic child failed'); process.exit(1); });
        '''
        # Standard npm installation shape: wrappers are deliberately not executed by TeamCode.
        for name, script in [('claude', '@anthropic-ai/claude-code/cli.js'), ('codex', '@openai/codex/bin/codex.js')]:
            file = root / 'node_modules' / script
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(agent_source, encoding='utf-8')
            (root / (name + '.cmd')).write_text('@echo THIS_SHIM_MUST_NOT_RUN\n', encoding='ascii')
        env['PATH'] = os.pathsep.join([str(root), str(node.parent), str(Path(os.environ['SystemRoot']) / 'System32')])
        env['ANTHROPIC_AUTH_TOKEN'] = 'inherited-must-be-removed'
        env['CLAUDE_CODE_OAUTH_TOKEN'] = 'inherited-must-be-removed'
        arguments = ['--dangerously-skip-permissions', '', 'équipe', 'two words', 'a"b',
                     'C:\\trailing\\', '& | < > %PATH% !value!', 'line\nbreak', '--', 'literal']
        for name in ('claude', 'codex'):
            record = root / (name + '.json')
            env['TEAMCODE_TEST_AGENT'] = name
            env['TEAMCODE_RUN_RECORD'] = str(record)
            result = command('run', name, '--', *arguments, stdin='stdin reaches the child\n', check=False)
            assert result.returncode == 37, f'{name} failed: {result.stdout}\n{result.stderr}'
            assert result.stdout == 'CHILD_STDOUT_OK\n' and 'CHILD_STDERR_OK' in result.stderr
            captured = json.loads(record.read_text(encoding='utf-8'))
            assert captured['args'] == arguments, 'Windows argument encoding changed the agent arguments'
            assert captured['cwd'] == str(Path.cwd())
            address = urllib.parse.urlsplit(captured['origin'])
            try:
                with socket.create_connection((address.hostname, address.port), timeout=.2):
                    raise AssertionError('Ephemeral proxy remained after child exit')
            except OSError:
                pass
        assert config.read_bytes() == original_config
        print('PASS: native Windows private ACLs, Unicode config, credential isolation, progressive SSE, literal agent arguments, npm launchers, stdio, exit code and cleanup', flush=True)

        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            service_port = listener.getsockname()[1]
        service_env = env.copy()
        service_installed = True
        command('service', 'install', '--port', str(service_port))
        settings = json.loads(config.with_name('service.json').read_text(encoding='utf-8'))
        service_origin = f'http://127.0.0.1:{service_port}'

        def service_health(expected=True):
            for _ in range(150):
                try:
                    request = urllib.request.Request(service_origin + '/_teamcode/service',
                        headers={'Authorization': 'Bearer ' + settings['localToken']})
                    with urllib.request.urlopen(request, timeout=1) as response:
                        running = json.load(response) == {'id': settings['id'], 'serverUrl': origin}
                except OSError:
                    running = False
                if running == expected:
                    return
                time.sleep(.2)
            print(command('service', 'logs', check=False).stdout, flush=True)
            raise AssertionError('Windows service did not reach expected state')

        service_health()
        command('service', 'install', '--port', str(service_port))
        assert json.loads(config.with_name('service.json').read_text(encoding='utf-8')) == settings
        assert 'Running: true' in command('service', 'status').stdout
        task = Path(settings['registration'])
        assert team_token not in task.read_text(encoding='utf-8') and settings['localToken'] not in task.read_text(encoding='utf-8')
        assert '<LogonType>InteractiveToken</LogonType>' in task.read_text(encoding='utf-8')
        # Reuse is checked through the actual native child, not just an identity ping.
        env['TEAMCODE_TEST_AGENT'] = 'claude'
        record = root / 'service-agent.json'
        env['TEAMCODE_RUN_RECORD'] = str(record)
        result = command('run', 'claude', '--', *arguments, stdin='stdin reaches the child\n', check=False)
        assert result.returncode == 37
        assert json.loads(record.read_text(encoding='utf-8'))['origin'] == service_origin
        service_health()

        def service_inference():
            request = urllib.request.Request(service_origin + '/v1/messages', b'{"model":"fixture"}',
                {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + settings['localToken']})
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read()

        stream_started.clear()
        stream_release.clear()
        with concurrent.futures.ThreadPoolExecutor() as pool:
            response = pool.submit(service_inference)
            assert stream_started.wait(5)
            stop = pool.submit(command, 'service', 'stop')
            time.sleep(2)
            assert not response.done(), 'Windows service interrupted an accepted stream'
            stream_release.set()
            assert b'"done":true' in response.result(timeout=10)
            stop.result(timeout=30)
        service_health(expected=False)
        command('service', 'start')
        service_health()
        command('service', 'restart')
        service_health()
        command('service', 'uninstall')
        service_health(expected=False)
        assert config.read_bytes() == original_config and not task.exists()
        command('service', 'uninstall')
        service_installed = False
        print('PASS: Windows Task Scheduler installation, idempotence, authenticated reuse, graceful drain, restart and uninstall', flush=True)
finally:
    stream_release.set()
    if service_installed:
        # Only this test's random task and isolated settings may be removed.
        cleanup = subprocess.run([binary, 'service', 'uninstall'], env=service_env, capture_output=True, timeout=60)
        service_installed = cleanup.returncode != 0
    if proxy is not None and proxy.poll() is None:
        proxy.terminate()
        proxy.wait(timeout=10)
    server.shutdown()
    server.server_close()
    if not service_installed:
        shutil.rmtree(directory)
    else:
        print('Windows service cleanup failed; isolated test directory retained for diagnosis.', flush=True)
