import unittest
import subprocess
import tempfile
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

    def test_redeployment_preserves_refreshed_authentication(self):
        compose = (ROOT / "docker-compose.production.yml").read_text(encoding="utf-8")
        initializer = compose.split("  postgres:", 1)[0]
        self.assertIn("if ! test -s /target/auth.json; then", initializer)
        self.assertNotIn("rm -rf /target", initializer)
        self.assertLess(initializer.index("if ! test -s"), initializer.index("cp -a /source"))
        self.assertLess(initializer.index("cp -a /source"), initializer.index("fi;"))

    def test_initializer_seeds_once_and_keeps_newer_login_on_repeat(self):
        compose = (ROOT / "docker-compose.production.yml").read_text(encoding="utf-8")
        script = compose.split("sh -ec '", 1)[1].split("fi;", 1)[0] + "fi;"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, target = root / "source", root / "target"
            source.mkdir()
            target.mkdir()
            (source / "auth.json").write_text("initial-test-auth")
            script = script.replace("/source", str(source)).replace("/target", str(target))
            subprocess.run(["sh", "-ec", script], check=True)
            self.assertEqual((target / "auth.json").read_text(), "initial-test-auth")
            (target / "auth.json").write_text("renewed-test-auth")
            (source / "auth.json").unlink()
            subprocess.run(["sh", "-ec", script], check=True)
            self.assertEqual((target / "auth.json").read_text(), "renewed-test-auth")


if __name__ == "__main__":
    unittest.main()
