#!/usr/bin/env python3
"""Actual native proxy: pre-header disconnect must stop server work through scoped cancellation."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.client import HTTPConnection
import json
import os
import re
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import traceback


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    args = parser.parse_args()
    entered = threading.Event()
    disconnected = threading.Event()
    survivor_entered = threading.Event()
    release_survivor = threading.Event()
    control_received = threading.Event()
    lock = threading.Lock()
    requests = {}
    cancelled_ids = set()
    errors = []
    token = secrets.token_urlsafe(32)
    local_token = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            if self.headers.get('Authorization') != 'Bearer ' + token:
                self.send_error(401)
                return
            match = re.fullmatch(r'/api/v1/requests/([0-9a-f-]{36})/cancel', self.path)
            if match:
                # This fixture models the server contract. Production uses shared Exposed
                # markers and cancels its handler, not this fixture's socket registry.
                with lock:
                    request_id = match.group(1)
                    cancelled_ids.add(request_id)
                    target = requests.get(request_id)
                assert target is not None, 'Cancellation targeted an unknown inference'
                model, connection = target
                assert model == 'synthetic-queued-request', 'Cancellation targeted a live peer'
                control_received.set()
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass  # Native engines may already have closed the inference connection.
                self.send_response(204)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            model = json.loads(body).get('model')
            request_id = self.headers.get('x-teamcode-client-request-id', '')
            assert re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', request_id), 'Missing SDK-owned request UUID'
            with lock:
                assert request_id not in requests, 'Inference identifiers must be unique'
                requests[request_id] = model, self.connection
            if model in ('synthetic-survivor', 'synthetic-next-request'):
                if model == 'synthetic-survivor':
                    survivor_entered.set()
                    assert release_survivor.wait(15), 'Test did not release the independent request'
                data = b'data: {"done":true}\n\n'
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            if model != 'synthetic-queued-request':
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
            except BrokenPipeError as error:
                # Winsock reports WSAESHUTDOWN after this fixture's authenticated
                # control handler disables reception. This is a terminated operation,
                # not a peer EOF; accept only that exact error for the cancelled UUID.
                # https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-recv
                if os.name != 'nt' or getattr(error, 'winerror', None) != 10058:
                    raise
                with lock:
                    assert request_id in cancelled_ids, 'Unexpected shutdown without matching cancellation'
                disconnected.set()
            except TimeoutError:
                pass

    class Server(ThreadingHTTPServer):
        def handle_error(self, *_):
            errors.append(traceback.format_exc())

    upstream = Server(('127.0.0.1', 0), Handler)
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
                    survivor = HTTPConnection('127.0.0.1', port, timeout=10)
                    auth = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + local_token}
                    try:
                        survivor.request('POST', '/v1/responses', json.dumps({'model': 'synthetic-survivor'}), auth)
                        assert survivor_entered.wait(5), 'Independent request never reached the upstream'
                        with socket.create_connection(('127.0.0.1', port), timeout=5) as downstream:
                            body = json.dumps({'model': 'synthetic-queued-request', 'stream': True, 'messages': []}).encode()
                            headers = (f'POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nAuthorization: Bearer {local_token}\r\n'
                                       f'Content-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n').encode()
                            downstream.sendall(headers + body)
                            assert entered.wait(5), 'The request never reached the synthetic upstream'
                            # Closing before any response must cancel only this request's transport.
                        assert disconnected.wait(5), 'Client disconnect left an upstream admission request running\n' + '\n'.join(errors)
                        assert control_received.wait(3), 'SDK did not send the authenticated cancellation control call'
                        release_survivor.set()
                        response = survivor.getresponse()
                        assert response.status == 200 and b'"done":true' in response.read(), 'Cancellation interrupted another request'
                    finally:
                        release_survivor.set()
                        survivor.close()
                    following = HTTPConnection('127.0.0.1', port, timeout=5)
                    try:
                        following.request('POST', '/v1/messages', json.dumps({'model': 'synthetic-next-request'}), auth)
                        response = following.getresponse()
                        assert response.status == 200 and b'"done":true' in response.read()
                    finally:
                        following.close()
                    assert len(cancelled_ids) == 1, 'Completed or surviving inference was cancelled'
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
    assert not errors, '\n'.join(errors)
    assert len(cancelled_ids) == 1, 'Shutdown cancelled a completed inference'
    print('Native pre-response disconnect sent scoped cancellation and stopped server work; concurrent and subsequent requests passed.')


if __name__ == '__main__':
    main()
