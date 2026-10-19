"""Loopback-only acceptance gate: remove native capacity at a real tool-result boundary."""
from contextlib import contextmanager
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import secrets
import threading
from urllib.parse import urlsplit


def completed_tool_ids(body, protocol):
    """Require both the call and its result; a plain second prompt is not a tool boundary."""
    if protocol == 'messages':
        blocks = [block for message in body.get('messages', [])
                  for block in message.get('content', []) if isinstance(block, dict)]
        calls = {b.get('id') for b in blocks if b.get('type') == 'tool_use'}
        results = {b.get('tool_use_id') for b in blocks if b.get('type') == 'tool_result'}
    else:
        items = body.get('input', [])
        if not isinstance(items, list):
            return set()
        calls = {i.get('call_id') for i in items if isinstance(i, dict) and i.get('type') in ('function_call', 'custom_tool_call')}
        results = {i.get('call_id') for i in items if isinstance(i, dict) and i.get('type') in ('function_call_output', 'custom_tool_call_output')}
    return (calls & results) - {None, ''}


class FallbackBoundary:
    def __init__(self, upstream, token, protocol, model, remove_capacity):
        origin = urlsplit(upstream)
        assert origin.scheme == 'http' and origin.hostname == '127.0.0.1' and origin.port
        self.upstream_port = origin.port
        self.token = token
        self.protocol = protocol
        self.model = model
        self.remove_capacity = remove_capacity
        self.lock = threading.Lock()
        self.switched = False
        self.records = []
        self.failure = None
        self.server = None

    def before_request(self, body):
        payload = json.loads(body)
        assert payload.get('model') == self.model
        completed = bool(completed_tool_ids(payload, self.protocol))
        with self.lock:
            if completed and not self.switched:
                assert self.records and any(r.get('status') == 200 and not r['after_switch'] for r in self.records)
                self.remove_capacity()
                self.switched = True
            record = {'after_switch': self.switched, 'completed_tool': completed}
            self.records.append(record)
            return record

    @contextmanager
    def serve(self):
        gate = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *_):
                pass  # Never log request bodies, headers, credentials or arbitrary paths.

            def do_GET(self):
                self.forward()

            def do_POST(self):
                self.forward()

            def forward(self):
                inference = self.command == 'POST' and self.path == '/v1/' + gate.protocol
                if not (inference or self.command == 'GET' and self.path == '/api/v1/capabilities'):
                    self.send_error(404)
                    return
                if not secrets.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + gate.token):
                    self.send_error(401)
                    return
                upstream = None
                started = False
                try:
                    # The native SDK sends a fixed ByteArray with a known Content-Length.
                    assert not self.headers.get('Transfer-Encoding')
                    length = int(self.headers.get('Content-Length', '0'))
                    assert 0 <= length <= 2 * 1024 * 1024
                    self.connection.settimeout(90)
                    body = self.rfile.read(length)
                    assert len(body) == length
                    record = gate.before_request(body) if inference else None
                    headers = {key: value for key, value in self.headers.items()
                               if key.lower() not in ('host', 'connection', 'transfer-encoding', 'content-length')}
                    upstream = http.client.HTTPConnection('127.0.0.1', gate.upstream_port, timeout=90)
                    upstream.request(self.command, self.path, body=body, headers=headers)
                    response = upstream.getresponse()
                    if record is not None:
                        record.update(status=response.status, provider=response.getheader('x-teamcode-provider'),
                                      model=response.getheader('x-teamcode-model'), request_id=response.getheader('x-teamcode-request-id'))
                    self.send_response(response.status)
                    for key, value in response.getheaders():
                        if key.lower() in ('content-type', 'x-teamcode-request-id', 'x-teamcode-provider', 'x-teamcode-model'):
                            self.send_header(key, value)
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    started = True
                    while chunk := response.read1(16 * 1024):
                        self.wfile.write(chunk)
                        self.wfile.flush()
                except Exception as error:
                    gate.failure = type(error).__name__  # Only a category, never raw exception content.
                    if not started:
                        self.send_error(502, 'Acceptance boundary failed')
                finally:
                    self.close_connection = True
                    if upstream is not None:
                        upstream.close()

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f'http://127.0.0.1:{self.server.server_port}'
        finally:
            self.server.shutdown()
            self.server.server_close()
            thread.join(timeout=5)


@contextmanager
def coding_fallback(request, token, origin, work, env, provider, model, protocol):
    """Change only disposable server account availability; keep route/history unchanged."""
    status, payload = request('/api/v1/accounts', token=token)
    assert status == 200
    accounts = [a for a in json.loads(payload) if a['provider'] == provider and not a['disabled']]
    assert accounts, 'No native provider capacity was imported for fallback acceptance'

    def configure(account, disabled):
        body = {key: account.get(key) for key in ('displayName', 'priority', 'poolId')}
        body['disabled'] = disabled
        assert request('/api/v1/accounts/' + account['id'], body, token, method='PATCH')[0] == 200

    def remove_capacity():
        for account in accounts:
            configure(account, True)

    gate = FallbackBoundary(origin, token, protocol, model, remove_capacity)
    try:
        with gate.serve() as address:
            config_root = work / 'teamcode-config'
            config = config_root / 'teamcode/config.json'
            config.parent.mkdir(parents=True, mode=0o700)
            with open(config, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
                json.dump({'serverUrl': address, 'accessToken': token}, output)
            env['XDG_CONFIG_HOME'] = str(config_root)
            yield gate
    finally:
        for account in accounts:
            configure(account, account['disabled'])


def verify_fallback(records, metadata, native_provider, native_model, fallback_provider, fallback_model):
    """Correlate client responses with successful streams, allowing only zero-charge retry attempts."""
    assert records and not records[0]['after_switch'], 'The task must begin on its native provider'
    assert any(r['after_switch'] for r in records), 'The task must exercise the fallback'
    assert not any(' failure request=' in line or ' settlement pending ' in line for line in metadata), 'Inference or settlement failed'
    dispatches = {re.search(r'request=([a-zA-Z0-9-]+)', line).group(1): line for line in metadata if ' dispatch ' in line}
    settlements = {re.search(r'request=([a-zA-Z0-9-]+)', line).group(1): line for line in metadata if ' settled ' in line}
    served = set()
    switched = False
    for record in records:
        assert not switched or record['after_switch'], 'Capacity removal must remain effective for the rest of the task'
        if record['after_switch'] and not switched:
            assert record['completed_tool'], 'Fallback must occur at an actual tool-result boundary'
        switched = record['after_switch']
        provider, model = (fallback_provider, fallback_model) if switched else (native_provider, native_model)
        assert record['status'] == 200 and record['provider'] == provider and record['model'] == model, 'The client received an error or an unexpected target'
        request_id = record['request_id']
        assert request_id in dispatches and request_id not in served, 'Every response must correlate to one provider dispatch'
        served.add(request_id)
        assert f'provider={provider} model={model}' in dispatches[request_id], 'Unexpected provider after capacity removal'
        terminal = 'message_stop' if provider == 'anthropic' else 'response.completed'
        assert request_id in settlements and 'units=unknown' not in settlements[request_id]
        assert f'terminal={terminal} failure=none' in settlements[request_id], 'The provider stream did not finish normally'
    for request_id in dispatches.keys() - served:
        assert request_id in settlements and f'settled request={request_id} units=0 ' in settlements[request_id], 'An unmatched attempt was not a zero-charge rejection'
