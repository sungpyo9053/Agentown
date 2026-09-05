import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class RuntimeAuthPackagingTest(unittest.TestCase):
    def test_backend_and_core_runtime_share_the_same_non_root_uid(self):
        backend = (ROOT / "backend" / "Dockerfile").read_text(encoding="utf-8")
        runtime = (ROOT / "core-runtime" / "Dockerfile").read_text(encoding="utf-8")

        self.assertIn("-u 10001 -G app app", backend)
        self.assertIn("--uid 10001 app", runtime)

    def test_production_compose_initializes_private_auth_for_runtime_uid(self):
        compose = (ROOT / "docker-compose.production.yml").read_text(encoding="utf-8")

        self.assertIn("codex-auth-init:", compose)
        self.assertIn("chown -R 10001:10001 /target", compose)
        self.assertIn("codex_auth:/home/app/.codex\n", compose)
        self.assertEqual(compose.count("codex_auth:/home/app/.codex:ro"), 1)
        self.assertIn('http://127.0.0.1:8090/ready', compose)


if __name__ == "__main__":
    unittest.main()
