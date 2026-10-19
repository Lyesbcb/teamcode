#!/usr/bin/env python3
"""Actual native proxy: disconnect before headers must close the pending upstream request."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    args = parser.parse_args()
    entered = threading.Event()
    disconnected = threading.Event()
    token = secrets.token_urlsafe(32)
    local_token = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            if self.headers.get('Authorization') != 'Bearer ' + token:
                self.send_error(401)
                return
            body = self.rfile.read(int(self.headers['Content-Length']))
            if json.loads(body).get('model') != 'synthetic-queued-request':
                self.send_error(400)
                return
            entered.set()
            # Do not send response headers. Observe the real client's socket closing.
            self.connection.settimeout(10)
            try:
                if not self.connection.recv(1):
                    disconnected.set()
            except ConnectionResetError:
                disconnected.set()
            except TimeoutError:
                pass

    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=upstream.serve_forever, daemon=True)
    thread.start()
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix='teamcode-queued-cancel-') as directory:
            root = Path(directory)
            config = root / 'teamcode/config.json'
            config.parent.mkdir(mode=0o700)
            with open(config, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
                json.dump({'serverUrl': f'http://127.0.0.1:{upstream.server_port}', 'accessToken': token}, output)
            with socket.socket() as free:
                free.bind(('127.0.0.1', 0))
                port = free.getsockname()[1]
            env = dict(os.environ, XDG_CONFIG_HOME=directory, TEAMCODE_LOCAL_TOKEN=local_token)
            with (root / 'proxy.log').open('w') as log:
                process = subprocess.Popen([str(args.binary.resolve()), 'proxy', 'start', '--port', str(port)], env=env, stdout=log, stderr=log)
                try:
                    deadline = time.monotonic() + 30
                    while True:
                        assert process.poll() is None, 'Owned proxy exited before readiness'
                        try:
                            with socket.create_connection(('127.0.0.1', port), timeout=.2):
                                break
                        except OSError:
                            assert time.monotonic() < deadline, 'Owned proxy readiness timed out'
                            time.sleep(.1)
                    with socket.create_connection(('127.0.0.1', port), timeout=5) as downstream:
                        body = json.dumps({'model': 'synthetic-queued-request', 'stream': True, 'messages': []}).encode()
                        headers = (f'POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nAuthorization: Bearer {local_token}\r\n'
                                   f'Content-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n').encode()
                        downstream.sendall(headers + body)
                        assert entered.wait(5), 'The request never reached the synthetic upstream'
                        # Closing without a response forces the native CIO/HTTP engine chain to cancel.
                    assert disconnected.wait(5), 'Client disconnect left an upstream admission request running'
                    print('Native pre-response disconnect cancelled the pending upstream request.')
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()  # This exact owned child only.
                        process.wait(timeout=5)
    finally:
        upstream.shutdown()
        upstream.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    main()
