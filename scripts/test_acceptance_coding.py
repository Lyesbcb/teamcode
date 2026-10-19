"""Guard the distinction between portable and default-reasoning acceptance."""
import unittest

from acceptance_coding import coding_command, conversation_blocks


class CodingProfileTest(unittest.TestCase):
    def test_default_profile_removes_inherited_disable_but_keeps_task_isolation(self):
        for agent in ('claude', 'codex'):
            with self.subTest(agent=agent):
                env = {'MAX_THINKING_TOKENS': '0', 'XDG_CONFIG_HOME': '/synthetic/config'}
                prompt = 'A literal prompt with spaces and $shell syntax'
                command = coding_command('/synthetic/teamcode', agent, 'test-model', prompt, env, 'default')
                self.assertEqual(['/synthetic/teamcode', 'run', agent, '--no-guidance', '--'], command[:5])
                self.assertEqual(prompt, command[-1])
                self.assertNotIn('MAX_THINKING_TOKENS', env)
                self.assertEqual('/synthetic/config', env['XDG_CONFIG_HOME'])
                self.assertFalse(any('model_reasoning_effort' in arg or 'alwaysThinkingEnabled' in arg for arg in command))
                if agent == 'claude':
                    self.assertNotIn('--settings', command)
                    self.assertIn('--strict-mcp-config', command)
                    self.assertEqual('Read,Edit,Bash', command[command.index('--tools') + 1])
                else:
                    self.assertIn('--ignore-user-config', command)
                    self.assertIn('web_search="disabled"', command)

    def test_portable_profile_is_explicitly_disabled_for_both_clients(self):
        env = {'MAX_THINKING_TOKENS': '4096'}
        command = coding_command('teamcode', 'claude', 'model', 'prompt', env, 'portable')
        self.assertEqual('0', env['MAX_THINKING_TOKENS'])
        self.assertEqual('{"alwaysThinkingEnabled":false}', command[command.index('--settings') + 1])
        env = {'MAX_THINKING_TOKENS': '0'}
        command = coding_command('teamcode', 'codex', 'model', 'prompt', env, 'portable')
        self.assertNotIn('MAX_THINKING_TOKENS', env)
        self.assertIn('model_reasoning_effort="none"', command)

    def test_codex_string_errors_cannot_hide_the_original_acceptance_failure(self):
        tool = {'type': 'tool_use', 'name': 'Read', 'input': {'file_path': '/synthetic'}}
        events = [{'type': 'error', 'message': 'Synthetic compatibility error'},
                  {'message': None}, {'message': {'content': 'plain text'}},
                  {'type': 'assistant', 'message': {'content': [tool, None, 'text']}}]
        self.assertEqual([tool], list(conversation_blocks(events)))

    def test_invalid_profile_or_agent_cannot_silently_choose_portable(self):
        for agent, profile in [('claude', 'defaut'), ('unknown', 'default')]:
            with self.assertRaises(ValueError):
                coding_command('teamcode', agent, 'model', 'prompt', {}, profile)


if __name__ == '__main__':
    unittest.main()
