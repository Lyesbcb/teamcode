"""Exercise the boundary harness without provider calls or credentials."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.parse import urlsplit

from acceptance_fallback import FallbackBoundary, coding_fallback, completed_tool_ids, verify_fallback


class FallbackBoundaryTest(unittest.TestCase):
    def test_evidence_requires_successful_correlated_streams_but_allows_safe_retries(self):
        records = [
            {'after_switch': False, 'completed_tool': False, 'status': 200, 'provider': 'codex', 'model': 'gpt-test', 'request_id': 'one'},
            {'after_switch': True, 'completed_tool': True, 'status': 200, 'provider': 'anthropic', 'model': 'claude-test', 'request_id': 'two'},
        ]
        metadata = [
            'TeamCode inference dispatch request=one provider=codex model=gpt-test',
            'TeamCode inference settled request=one units=5 terminal=response.completed failure=none',
            'TeamCode inference dispatch request=retry provider=anthropic model=claude-test',
            'TeamCode inference settled request=retry units=0 terminal=none failure=none',
            'TeamCode inference dispatch request=two provider=anthropic model=claude-test',
            'TeamCode inference settled request=two units=7 terminal=message_stop failure=none',
        ]
        def verify(rows, lines):
            verify_fallback(rows, lines, 'codex', 'gpt-test', 'anthropic', 'claude-test')
        verify(records, metadata)
        for before, after in [('units=0', 'units=unknown'), ('units=7', 'units=unknown'),
                              ('terminal=message_stop', 'terminal=content_block_delta'), ('failure=none', 'failure=translation')]:
            with self.subTest(before=before):
                with self.assertRaises(AssertionError):
                    verify(records, [line.replace(before, after) for line in metadata])
        for patch in ({'status': 429}, {'completed_tool': False}, {'request_id': 'absent'}, {'provider': 'codex'}):
            with self.subTest(patch=patch):
                with self.assertRaises(AssertionError):
                    verify([records[0], records[1] | patch], metadata)
        with self.assertRaises(AssertionError):
            verify(records, metadata + ['TeamCode inference failure request=two category=translation_failed'])

    def test_disposable_config_and_account_settings_are_scoped_and_restored_on_failure(self):
        accounts = [{'id': 'one', 'provider': 'codex', 'displayName': 'Synthetic', 'disabled': False, 'priority': 7, 'poolId': 'protected'},
                    {'id': 'two', 'provider': 'anthropic', 'disabled': False}]
        changes = []

        def request(path, data=None, token=None, method=None):
            self.assertEqual('synthetic-token', token)
            if path == '/api/v1/accounts':
                return 200, json.dumps(accounts)
            self.assertEqual('/api/v1/accounts/one', path)
            self.assertEqual('PATCH', method)
            changes.append(data)
            return 200, '{}'

        with tempfile.TemporaryDirectory() as directory:
            env = {}
            with self.assertRaisesRegex(RuntimeError, 'synthetic agent failure'):
                with coding_fallback(request, 'synthetic-token', 'http://127.0.0.1:12345', Path(directory), env,
                                     'codex', 'test-model', 'responses') as gate:
                    saved = Path(env['XDG_CONFIG_HOME']) / 'teamcode/config.json'
                    content = json.loads(saved.read_text())
                    self.assertEqual('synthetic-token', content['accessToken'])
                    self.assertEqual(f'http://127.0.0.1:{gate.server.server_port}', content['serverUrl'])
                    self.assertEqual(0o600, saved.stat().st_mode & 0o777)
                    gate.remove_capacity()
                    raise RuntimeError('synthetic agent failure')
        self.assertEqual([{'displayName': 'Synthetic', 'priority': 7, 'poolId': 'protected', 'disabled': disabled}
                          for disabled in (True, False)], changes)

    def test_only_matching_tool_calls_and_results_trigger_boundary(self):
        self.assertFalse(completed_tool_ids({'input': 'hello'}, 'responses'))
        self.assertFalse(completed_tool_ids({'messages': [{'content': 'hello'}]}, 'messages'))
        for protocol, payload in [
            ('messages', {'messages': [{'role': 'assistant', 'content': [{'type': 'tool_use', 'id': 'tool-1'}]},
                                       {'role': 'user', 'content': [{'type': 'tool_result', 'tool_use_id': 'tool-1'}]}]}),
            ('responses', {'input': [{'type': 'custom_tool_call', 'call_id': 'tool-1'},
                                     {'type': 'custom_tool_call_output', 'call_id': 'tool-1'}]}),
            ('responses', {'input': [{'type': 'function_call', 'call_id': 'tool-1'},
                                     {'type': 'function_call_output', 'call_id': 'tool-1'}]}),
        ]:
            self.assertEqual({'tool-1'}, completed_tool_ids(payload, protocol))
        self.assertFalse(completed_tool_ids({'input': [{'type': 'function_call', 'call_id': 'one'},
                                                      {'type': 'function_call_output', 'call_id': 'other'}]}, 'responses'))

    def test_capacity_changes_before_unchanged_tool_history_is_forwarded(self):
        received = []
        changes = []
        state = {'provider': 'codex'}

        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                received.append((self.rfile.read(int(self.headers['Content-Length'])), state['provider']))
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('x-teamcode-provider', state['provider'])
                self.send_header('x-teamcode-model', 'test-model')
                self.end_headers()
                self.wfile.write(b'data: {"type":"fixture"}\n\n')

        server = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def remove_capacity():
            changes.append(len(received))
            state['provider'] = 'anthropic'

        try:
            gate = FallbackBoundary(f'http://127.0.0.1:{server.server_port}', 'synthetic-token', 'responses', 'test-model', remove_capacity)
            initial = json.dumps({'model': 'test-model', 'input': 'Use a tool.'}).encode()
            history = json.dumps({'model': 'test-model', 'input': [
                {'type': 'custom_tool_call', 'name': 'exec', 'namespace': 'functions', 'call_id': 'call-1', 'input': 'text("Café 🌍");'},
                {'type': 'custom_tool_call_output', 'call_id': 'call-1', 'output': 'fixture-result'},
            ]}, ensure_ascii=False).encode()
            with gate.serve() as address:
                port = urlsplit(address).port
                def send(body, token='synthetic-token'):
                    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
                    try:
                        connection.request('POST', '/v1/responses', body, {'Authorization': 'Bearer ' + token})
                        response = connection.getresponse()
                        data = response.read()
                        return response.status, data
                    finally:
                        connection.close()
                self.assertEqual(401, send(history, 'wrong-token')[0])
                self.assertFalse(changes)
                self.assertEqual((200, b'data: {"type":"fixture"}\n\n'), send(initial))
                self.assertEqual((200, b'data: {"type":"fixture"}\n\n'), send(history))
                self.assertEqual(200, send(history)[0])
            self.assertEqual([1], changes, 'Capacity removal must occur once, after native output and before replay')
            self.assertEqual([(initial, 'codex'), (history, 'anthropic'), (history, 'anthropic')], received)
            self.assertEqual([False, True, True], [r['after_switch'] for r in gate.records])
            self.assertTrue(gate.switched)
            self.assertIsNone(gate.failure)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
