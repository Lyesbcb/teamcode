"""Opt-in real-agent coding acceptance, called by acceptance-provider.py only."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time


def run_agent(command, work, env):
    # Let TeamCode forward termination to its own child and drain its proxy before
    # forcing only this process to exit. Never include command/output in exceptions.
    process = subprocess.Popen(command, cwd=work, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        stdout, stderr = process.communicate(timeout=180)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate(timeout=5)
        raise RuntimeError('The isolated coding task exceeded its 180-second deadline.') from None
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


def run_coding_tasks(args, binary, root, env, request, token, log):
    policy = json.loads(request('/api/v1/policy/models', token=token)[1])
    policy['allowedEfforts'] = list(dict.fromkeys(policy['allowedEfforts'] + ['none']))
    assert request('/api/v1/policy/models', policy, token, method='PUT')[0] == 200
    target_codex = args.codex_model or 'gpt-6-luna'
    directions = ('claude', 'codex') if args.coding_task == 'both' else (args.coding_task,)
    for agent in directions:
        executable = getattr(args, agent) or shutil.which(agent)
        assert executable, f'{agent} must be installed'
        executable = Path(executable).resolve()
        work = root / ('coding-' + agent)
        work.mkdir()
        agent_bin = work / 'bin'
        agent_bin.mkdir()
        (agent_bin / agent).symlink_to(executable)
        initial = 'def unique_positive(values):\n    return sorted(values)\n'
        source = work / 'numbers_util.py'
        source.write_text(initial)
        tests = work / 'test_numbers_util.py'
        tests.write_text('''import unittest
from numbers_util import unique_positive

class NumbersTest(unittest.TestCase):
    def test_filters_and_preserves_first_seen_order(self):
        self.assertEqual(unique_positive([3, -1, 2, 3, 0, 1, 2]), [3, 2, 1])
    def test_empty(self):
        self.assertEqual(unique_positive([]), [])
    def test_no_positive(self):
        self.assertEqual(unique_positive([-3, 0, -1]), [])
    def test_does_not_mutate_input(self):
        values = [2, 1, 2]
        unique_positive(values)
        self.assertEqual(values, [2, 1, 2])

if __name__ == '__main__':
    unittest.main()
''')
        digest = hashlib.sha256(tests.read_bytes()).hexdigest()
        incoming = args.model if agent == 'claude' else target_codex
        provider = 'codex' if agent == 'claude' else 'anthropic'
        target = target_codex if agent == 'claude' else args.model
        rule = {'id': 'coding-' + agent, 'pattern': incoming, 'targets': [{'provider': provider, 'model': target}]}
        assert request('/api/v1/routes/' + rule['id'], rule, token, method='PUT')[0] == 200
        child_env = {key: value for key, value in env.items()
                     if not key.startswith(('ANTHROPIC_', 'CLAUDE_', 'CODEX_', 'OPENAI_', 'CMUX_', 'TEAMCODE_')) and key != 'CLAUDECODE'}
        child_env.update(PATH=str(agent_bin) + os.pathsep + child_env['PATH'],
                         CLAUDE_CONFIG_DIR=str(work / 'claude-config'), CODEX_HOME=str(work / 'codex-config'),
                         CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1', DISABLE_AUTOUPDATER='1',
                         DISABLE_TELEMETRY='1', DISABLE_ERROR_REPORTING='1')
        Path(child_env['CODEX_HOME']).mkdir()
        Path(child_env['CLAUDE_CONFIG_DIR']).mkdir()
        prompt = (f'Work only in {work.resolve()}. Read numbers_util.py and test_numbers_util.py there. '
                  'First run python3 -m unittest -v to reproduce the failure, before editing. '
                  'Fix unique_positive to return each strictly positive integer once, in first-seen order, '
                  'without changing the input. Edit only numbers_util.py. Do not change the tests. '
                  'Run python3 -m unittest -v and fix any failure. Use tools to read, edit and test; '
                  'do not just describe a patch. Do not access the network or other directories. '
                  'Finish with CODING_TASK_OK only after the tests pass.')
        command = [binary, 'run', agent, '--no-guidance', '--']
        if agent == 'claude':
            # Begin with the documented portable profile; reasoning acceptance is a separate gate.
            child_env['MAX_THINKING_TOKENS'] = '0'
            command += ['--bare', '--setting-sources', '', '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}',
                        '--tools', 'Read,Edit,Bash', '--allowedTools', 'Read,Edit,Bash(python3 -m unittest*)',
                        '--no-session-persistence', '--disable-slash-commands', '--max-turns', '8',
                        '--model', incoming, '--settings', '{"alwaysThinkingEnabled":false}',
                        '--system-prompt', 'Complete the small local coding task using the supplied tools. Stay in the current directory.',
                        '--verbose', '--output-format', 'stream-json', '-p', prompt]
        else:
            command += ['--no-daemon', 'exec', '--ignore-user-config', '--ignore-rules', '--ephemeral',
                        '--skip-git-repo-check', '--sandbox', 'workspace-write', '--json',
                        '-c', 'model_reasoning_effort="none"', '-c', 'web_search="disabled"',
                        '--model', incoming, prompt]
        log_offset = len(log.read_text())
        result = run_agent(command, work, child_env)
        events = []
        for line in result.stdout.splitlines():
            try:
                events.append(json.loads(line))
            except ValueError:
                pass
        if result.returncode != 0 or 'CODING_TASK_OK' not in result.stdout:
            diagnostics = (result.stdout + result.stderr).lower()
            markers = ['translation_unsupported', 'unsupported', 'pool_unavailable', 'stream disconnected',
                       'invalid', 'permission', 'sandbox', 'timeout', 'thinking', 'reasoning', 'tool', 'error',
                       'max_tokens', 'effort', 'budget', 'fields', 'temperature', 'input_schema', 'stream', 'authentication',
                       'invalid_thinking', 'invalid_max_tokens', 'invalid_effort', 'invalid_request', 'api error']
            print('Coding task failure categories:', [m for m in markers if m in diagnostics], flush=True)
            print('Coding task event types:', sorted({str(event.get('type')) for event in events}), flush=True)
            # Match only public, source-authored compatibility messages. Never print
            # arbitrary client output, tool arguments, prompts or provider bodies.
            adapters = Path(__file__).resolve().parents[1] / 'infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/protocols'
            for source_file in adapters.glob('*.kt'):
                for message in re.findall(r'unsupported\("([^"$]+)"\)', source_file.read_text()):
                    if message.lower() in diagnostics:
                        print('Matched compatibility diagnostic:', message, flush=True)
            for event in events:
                for block in event.get('message', {}).get('content', []):
                    if isinstance(block, dict) and block.get('type') in ('tool_use', 'tool_result'):
                        print('Tool event:', block['type'], 'name=' + str(block.get('name')), 'is_error=' + str(block.get('is_error')), 'fields=' + ','.join(sorted(block)), flush=True)
                        if block['type'] == 'tool_use':
                            print('Tool argument types:', {key: type(value).__name__ for key, value in block.get('input', {}).items()}, flush=True)
                        else:
                            content = str(block.get('content', '')).lower()
                            print('Tool error categories:', [word for word in ['validation', 'invalid', 'null', 'pages', 'permission', 'denied', 'not found', 'does not exist', 'absolute'] if word in content], flush=True)
            for line in log.read_text()[log_offset:].splitlines():
                if line.startswith('TeamCode inference '): print(line, flush=True)
            raise AssertionError(f'{agent} coding task failed; raw output withheld to protect credentials.')
        assert token not in result.stdout + result.stderr
        assert source.read_text() != initial, 'The agent did not edit the source'
        assert hashlib.sha256(tests.read_bytes()).hexdigest() == digest, 'The agent changed acceptance tests'
        checked = subprocess.run(['python3', '-m', 'unittest', '-v'], cwd=work, env=child_env,
                                 capture_output=True, text=True, timeout=15)
        assert checked.returncode == 0, 'Independent post-agent tests failed'
        if agent == 'claude':
            calls = [block for event in events if event.get('type') == 'assistant'
                     for block in event.get('message', {}).get('content', []) if block.get('type') == 'tool_use']
            assert {'Read', 'Edit', 'Bash'} <= {call.get('name') for call in calls}, 'Read/edit/test tools were not all exercised'
            assert any('python3 -m unittest' in call.get('input', {}).get('command', '') for call in calls)
        else:
            assert any(event.get('type') == 'turn.completed' for event in events), 'Codex never completed the turn'
            assert any('python3 -m unittest' in event.get('item', {}).get('command', '')
                       for event in events if event.get('type') == 'item.completed'), 'Codex did not run the tests'
        deadline = time.monotonic() + 5
        while True:
            metadata = [line for line in log.read_text()[log_offset:].splitlines() if line.startswith('TeamCode inference ')]
            dispatched = {re.search(r'request=([a-zA-Z0-9-]+)', line).group(1) for line in metadata if ' dispatch ' in line}
            settled = {re.search(r'request=([a-zA-Z0-9-]+)', line).group(1) for line in metadata if ' settled ' in line and 'units=unknown' not in line}
            if dispatched <= settled: break
            assert time.monotonic() < deadline, 'Coding task usage did not settle'
            time.sleep(.05)
        dispatches = [line for line in metadata if ' dispatch ' in line]
        assert len(dispatches) >= 2, 'A multi-turn tool task must dispatch multiple requests'
        assert all(f'provider={provider} model={target}' in line for line in dispatches), 'The coding task escaped its forced route'
        print(f'Real {agent} coding task passed via {provider}:{target}: source corrected, tools executed, four independent tests passed, {len(dispatches)} requests settled.', flush=True)
        for line in metadata: print(line, flush=True)
