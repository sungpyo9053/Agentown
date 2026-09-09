import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tframex.models.primitives import Message

from agentown_tframex_adapter.codex_llm import CodexCliLLMWrapper, codex_auth_file


class _Process:
    returncode = 0

    async def communicate(self, _input):
        return b'{"answer":"ok"}', b""


class CodexWritableHomeTest(unittest.IsolatedAsyncioTestCase):
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
