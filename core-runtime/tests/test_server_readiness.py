import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.responses import JSONResponse

from agentown_tframex_adapter.server import readiness


class ServerReadinessTest(unittest.TestCase):
    def test_ready_requires_readable_codex_auth_and_executable(self):
        with tempfile.TemporaryDirectory() as raw:
            Path(raw, "auth.json").write_text("{}", encoding="utf-8")
            with patch.dict(os.environ, {"CODEX_HOME": raw, "AGENTOWN_CODEX_COMMAND": "/usr/local/bin/codex"}, clear=False), \
                 patch("agentown_tframex_adapter.server.shutil.which", return_value="/usr/local/bin/codex"):
                self.assertEqual(readiness()["status"], "READY")

    def test_missing_or_unreadable_auth_fails_closed_without_exposing_path(self):
        with tempfile.TemporaryDirectory() as raw:
            auth = Path(raw, "auth.json")
            auth.write_text("secret", encoding="utf-8")
            with patch.dict(os.environ, {"CODEX_HOME": raw, "AGENTOWN_CODEX_COMMAND": "codex"}, clear=False), \
                 patch("agentown_tframex_adapter.server.shutil.which", return_value="/usr/local/bin/codex"), \
                 patch("agentown_tframex_adapter.server.os.access", return_value=False):
                response = readiness()

        self.assertIsInstance(response, JSONResponse)
        self.assertEqual(response.status_code, 503)
        body = json.loads(response.body)
        self.assertEqual(body, {"status": "NOT_READY", "code": "CODEX_AUTH_UNAVAILABLE"})
        self.assertNotIn(raw, response.body.decode())


if __name__ == "__main__":
    unittest.main()
