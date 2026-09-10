import os
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tframex.models.primitives import Message

from agentown_tframex_adapter.codex_llm import CodexCliLLMWrapper, codex_auth_file, safe_cli_failure


class _Process:
    returncode = 0

    async def communicate(self, _input):
        return b'{"answer":"ok"}', b""


class CodexWritableHomeTest(unittest.IsolatedAsyncioTestCase):
    async def test_web_search_is_opt_in_and_requires_completed_search_events(self):
        with tempfile.TemporaryDirectory() as shared_home:
            Path(shared_home, "auth.json").write_text('{}', encoding="utf-8")
            completed = {"type": "item.completed", "item": {"id": "search-1", "type": "web_search", "action": {"type": "search"}}}
            answer = {"type": "item.completed", "item": {"id": "answer", "type": "agent_message", "text": '{"answer":"researched"}'}}
            for events, expected in [([answer], False), ([{**completed, "type": "item.started"}, answer], False), ([completed, completed, answer], True)]:
                class Process(_Process):
                    async def communicate(self, _input):
                        return '\n'.join(json.dumps(event) for event in events).encode(), b""
                async def start(*args, **kwargs):
                    self.assertIn("tools.web_search=true", args)
                    self.assertIn('--json', args)
                    self.assertIn('shell_tool', args)
                    self.assertIn('agents.enabled=false', args)
                    return Process()
                with patch.dict(os.environ, {"CODEX_HOME": shared_home}), \
                     patch("agentown_tframex_adapter.codex_llm.shutil.which", return_value="/usr/local/bin/codex"), \
                     patch("agentown_tframex_adapter.codex_llm.asyncio.create_subprocess_exec", side_effect=start):
                    wrapper = CodexCliLLMWrapper(web_search=True)
                    if expected:
                        result = await wrapper.chat_completion([Message(role="user", content="research")])
                        self.assertEqual(result.content, '{"answer":"researched"}')
                        self.assertEqual(wrapper.searches_performed, 1)
                    else:
                        with self.assertRaisesRegex(RuntimeError, "WEB_RESEARCH_NOT_PERFORMED"):
                            await wrapper.chat_completion([Message(role="user", content="research")])
    def test_provider_errors_never_expose_private_logs(self):
        for marker in ("401", "refresh_token_reused", "refresh token was already used", "Failed to refresh token"):
            message = safe_cli_failure(f"ERROR private-path {marker} secret=private-value".encode())
            self.assertIn("AI_AUTH_RECONNECT_REQUIRED", message)
            self.assertNotIn("private", message)
        message = safe_cli_failure(b"ERROR private-path secret=private-value")
        self.assertIn("AI_EXECUTION_FAILED", message)
        self.assertNotIn("private", message)

    def test_default_cli_home_requires_no_extra_environment_variable(self):
        with patch.dict(os.environ, {}, clear=True), patch("pathlib.Path.home", return_value=Path("/test-user")):
            self.assertEqual(codex_auth_file(), Path("/test-user/.codex/auth.json"))

    def test_explicit_cli_home_does_not_fall_back_to_another_identity(self):
        with patch.dict(os.environ, {"CODEX_HOME": "/configured-auth"}, clear=True):
            self.assertEqual(codex_auth_file(), Path("/configured-auth/auth.json"))

    async def test_read_only_shared_auth_is_copied_to_private_writable_home(self):
        with tempfile.TemporaryDirectory() as shared_home:
            Path(shared_home, "auth.json").write_text('{"token":"secret"}', encoding="utf-8")
            observed = {}

            async def start(*_args, **kwargs):
                self.assertIn("tools.web_search=false", _args)
                self.assertNotIn("--json", _args)
                run_home = kwargs["env"]["CODEX_HOME"]
                observed["run_home"] = run_home
                self.assertNotEqual(run_home, shared_home)
                self.assertEqual(kwargs["env"]["HOME"], run_home)
                self.assertEqual(Path(run_home, "auth.json").read_text(encoding="utf-8"), '{"token":"secret"}')
                self.assertTrue(os.access(run_home, os.W_OK))
                return _Process()

            with patch.dict(os.environ, {"CODEX_HOME": shared_home}, clear=False), \
                 patch("agentown_tframex_adapter.codex_llm.shutil.which", return_value="/usr/local/bin/codex"), \
                 patch("agentown_tframex_adapter.codex_llm.asyncio.create_subprocess_exec", side_effect=start):
                result = await CodexCliLLMWrapper(command="codex").chat_completion([Message(role="user", content="hello")])

            self.assertEqual(result.content, '{"answer":"ok"}')
            self.assertFalse(Path(observed["run_home"]).exists())


if __name__ == "__main__":
    unittest.main()
