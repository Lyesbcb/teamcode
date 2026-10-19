#!/usr/bin/env python3
"""Exercise actual native installation/update/rollback using two already signed local bundles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--first-bundle', type=Path, required=True)
    parser.add_argument('--second-bundle', type=Path, required=True)
    parser.add_argument('--service', action='store_true', help='Also exercise the actual owned OS service; Linux needs a user manager.')
    args = parser.parse_args()
    binary = str(args.binary.resolve())
    target = ('windows-x64' if os.name == 'nt' else
              ('macos-' if platform.system() == 'Darwin' else 'linux-') +
              ('arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x64'))
    first, second = args.first_bundle.resolve(), args.second_bundle.resolve()
    manifests = [json.loads((directory / 'manifest.json').read_bytes()) for directory in (first, second)]
    digests = [hashlib.sha256((directory / 'manifest.json').read_bytes()).hexdigest() for directory in (first, second)]
    assert digests[0] != digests[1], 'Use distinct signed manifests to exercise selection and rollback'
    inputs = {path: hashlib.sha256(path.read_bytes()).hexdigest() for directory, manifest in zip((first, second), manifests)
              for path in (directory / 'manifest.json', directory / 'manifest.sig', directory / manifest['assets'][target]['fileName'])}

    with tempfile.TemporaryDirectory(prefix='teamcode-installation-') as directory:
        root = Path(directory) / "application é space's"
        install_root = root / 'installation'
        config_root = root / 'configuration'
        env = dict(os.environ, XDG_CONFIG_HOME=str(config_root))
        root.mkdir()
        launcher = install_root / 'bin' / ('teamcode.exe' if os.name == 'nt' else 'teamcode')

        def run(executable, arguments, error=None, input_text=None):
            io = {'stdin': subprocess.DEVNULL} if input_text is None else {'input': input_text}
            result = subprocess.run([str(executable), *arguments], env=env, **io,
                                    capture_output=True, text=True, timeout=45)
            output = result.stdout + result.stderr
            if error:
                assert result.returncode != 0 and error in output, f'Expected {error}; exit={result.returncode}: {output}'
            else:
                assert result.returncode == 0, f'Native installation operation failed: {output}'
            return output

        def state(): return json.loads((install_root / 'current.json').read_bytes())
        def selected(index):
            assert run(launcher, ['version']).strip() == 'TeamCode ' + manifests[index]['version']
            assert state()['current']['manifestSha256'] == digests[index]
            assert not (install_root / 'transaction.json').exists(), 'A completed operation left a pending journal'

        run(binary, ['install', '--bundle', str(first), '--root', str(install_root)])
        selected(0)
        original_launcher = hashlib.sha256(launcher.read_bytes()).hexdigest()
        run(binary, ['install', '--bundle', str(first), '--root', str(install_root)])
        selected(0)
        run(launcher, ['update', '--bundle', str(second)])
        selected(1)
        assert state()['previous']['manifestSha256'] == digests[0]
        assert hashlib.sha256(launcher.read_bytes()).hexdigest() == original_launcher, 'The running launcher was replaced'
        run(launcher, ['update', '--rollback'])
        selected(0)
        assert state()['previous']['manifestSha256'] == digests[1]
        for digest in digests: assert (install_root / 'versions' / digest).is_dir(), 'A previous payload was deleted'

        # Reconstruct the durable state left by an interrupted selection before its startup commit.
        before = state()
        after = dict(before, current=before['previous'], previous=before['current'])
        (install_root / 'transaction.json').write_text(json.dumps({'schemaVersion': 1, 'before': before, 'after': after,
                                                                  'serviceConfig': None, 'restartService': False}), encoding='utf-8')
        (install_root / 'current.json').write_text(json.dumps(after), encoding='utf-8')
        (install_root / 'transaction.json').chmod(0o600)
        selected(0)
        assert state() == before, 'Interrupted transaction did not restore the exact previous selection'

        corrupt = root / 'corrupt bundle'
        corrupt.mkdir()
        for name in ('manifest.json', 'manifest.sig'): shutil.copyfile(first / name, corrupt / name)
        (corrupt / manifests[0]['assets'][target]['fileName']).write_bytes(b'UNTRUSTED_EXECUTABLE_MUST_NEVER_RUN')
        rejected_root = root / 'rejected installation'
        run(binary, ['install', '--bundle', str(corrupt), '--root', str(rejected_root)], error='release_binary_invalid')
        assert not (rejected_root / 'current.json').exists()
        selected(0)
        assert state() == before

        foreign = root / 'unrelated directory'
        foreign.mkdir()
        (foreign / 'keep.txt').write_bytes(b'preserve this user file')
        run(binary, ['install', '--bundle', str(first), '--root', str(foreign)], error='release_directory_not_owned')
        assert (foreign / 'keep.txt').read_bytes() == b'preserve this user file'
        assert sorted(path.name for path in foreign.iterdir()) == ['keep.txt']
        assert not config_root.exists(), 'Installation without login created team configuration'
        if args.service:
            home = root / 'home'
            home.mkdir()
            env['HOME'] = str(home)
            token = secrets.token_hex(32)
            online = True
            api_version = 1
            calls = []
            class Handler(BaseHTTPRequestHandler):
                def log_message(self, *_): pass
                def do_GET(self):
                    calls.append(self.path)
                    if not online or self.headers.get('Authorization') != 'Bearer ' + token:
                        self.send_error(503); return
                    body = ({'id': 'fixture-member', 'username': 'fixture', 'role': 'MEMBER', 'disabled': False,
                             'passwordChangeRequired': False} if self.path.endswith('/me') else
                            {'apiVersion': api_version, 'serverVersion': 'fixture', 'mode': 'fixture', 'features': []})
                    data = json.dumps(body).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers(); self.wfile.write(data)
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            origin = f'http://127.0.0.1:{server.server_port}'
            agent = None
            release = root / 'release-quiet-agent'
            installed_service = False
            try:
                run(launcher, ['configure', '--server', origin], input_text=token + '\n')
                configuration = config_root / 'teamcode/config.json'
                saved_configuration = configuration.read_bytes()
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0)); local_port = listener.getsockname()[1]
                installed_service = True
                run(launcher, ['service', 'install', '--port', str(local_port)])
                settings_path = config_root / 'teamcode/service.json'
                saved_settings = settings_path.read_bytes()
                settings = json.loads(saved_settings)
                assert Path(settings['executable']).resolve() == launcher.resolve(), 'Service registered a versioned payload instead of the stable launcher'
                def identity(version):
                    for _ in range(80):
                        try:
                            request = urllib.request.Request(f'http://127.0.0.1:{local_port}/_teamcode/service',
                                headers={'Authorization': 'Bearer ' + settings['localToken']})
                            with urllib.request.urlopen(request, timeout=1) as response: body = json.load(response)
                            if body.get('version') == version and body.get('id') == settings['id']: return
                        except OSError: pass
                        time.sleep(.1)
                    raise AssertionError('Managed service did not start the selected version')
                identity(manifests[0]['version'])
                api_version = 2
                try: run(launcher, ['update', '--bundle', str(second)], error='release_candidate_incompatible')
                finally: api_version = 1
                selected(0); identity(manifests[0]['version'])
                if os.name != 'nt':
                    agents = root / 'fake agents'; agents.mkdir()
                    ready = root / 'quiet-agent-ready'
                    stub = agents / 'claude'
                    stub.write_text('#!' + sys.executable + '\nimport os,pathlib,time\n'
                                    'pathlib.Path(os.environ["TEAMCODE_INSTALLATION_READY"]).touch()\n'
                                    'while not pathlib.Path(os.environ["TEAMCODE_INSTALLATION_RELEASE"]).exists(): time.sleep(.05)\n')
                    stub.chmod(0o700)
                    agent_env = dict(env, PATH=str(agents) + os.pathsep + os.environ['PATH'],
                        TEAMCODE_INSTALLATION_READY=str(ready), TEAMCODE_INSTALLATION_RELEASE=str(release))
                    agent = subprocess.Popen([str(launcher), 'run', 'claude', '--no-guidance'], env=agent_env,
                                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    for _ in range(200):
                        if ready.exists(): break
                        assert agent.poll() is None, 'Synthetic quiet agent exited before taking its lease'
                        time.sleep(.05)
                    else: raise AssertionError('Synthetic quiet agent did not start')
                    run(launcher, ['update', '--bundle', str(second)], error='release_installation_busy')
                    selected(0); identity(manifests[0]['version'])
                    assert agent.poll() is None, 'Update terminated an active agent'
                    release.touch()
                    agent.communicate(timeout=10)
                    assert agent.returncode == 0
                run(launcher, ['update', '--bundle', str(second)])
                selected(1); identity(manifests[1]['version'])
                online = False
                before_offline = len(calls)
                run(launcher, ['update', '--rollback'])
                selected(0); identity(manifests[0]['version'])
                assert len(calls) == before_offline, 'Offline rollback contacted the central server'
                assert configuration.read_bytes() == saved_configuration
                assert settings_path.read_bytes() == saved_settings
            finally:
                release.touch()
                if agent is not None and agent.poll() is None:
                    try: agent.communicate(timeout=10)
                    except subprocess.TimeoutExpired: agent.terminate(); agent.communicate(timeout=5)
                try:
                    if installed_service: run(launcher, ['service', 'uninstall'])
                finally:
                    server.shutdown(); server.server_close(); worker.join(timeout=3)
        if os.name != 'nt':
            assert install_root.stat().st_mode & 0o077 == 0
            assert launcher.stat().st_mode & 0o777 == 0o700
            record = install_root / 'current.json'
            record.chmod(0o644)
            try: run(launcher, ['version'], error='release_private_path_invalid')
            finally: record.chmod(0o600)
            selected(0)
        assert hashlib.sha256(launcher.read_bytes()).hexdigest() == original_launcher
        for path, digest in inputs.items(): assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'Source bundle changed'
    extra = ', actual service restart/version checks and quiet-agent protection' if args.service else ''
    print('PASS: native signed installation, immutable launcher, update, offline rollback, interrupted-selection recovery, integrity refusal, private Unicode paths and preserved input/configuration' + extra + '.')


if __name__ == '__main__':
    main()
