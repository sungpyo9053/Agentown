import json
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from harness.deploy_adapter import SshReleaseAdapter
from harness.release import ReleaseError, ReleaseManager, atomic_json, redact, run_fake_deploy_command


class ReleaseManagerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "contracts").mkdir()
        (self.root / "runs" / "run-1").mkdir(parents=True)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "release@test.invalid"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "Release Test"], cwd=self.root, check=True)
        (self.root / "app.txt").write_text("safe\n")
        subprocess.run(["git", "add", "app.txt"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-qm", "safe"], cwd=self.root, check=True)
        self.sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        self.verification = self.root / "runs" / "run-1" / "verification-report.json"
        atomic_json(self.verification, {"commit_sha": self.sha, "commands": [{"command": "test", "status": "PASSED"}]})
        self.active = self.root / "contracts" / "active-release.json"
        self.contract = {
            "release_id": "release-1", "application": "agentown", "environment": "FAKE", "approved_commit_sha": self.sha,
            "source_branch": "test", "planner_task_id": "task-1", "planner_decision": "APPROVED",
            "verification_report": "runs/run-1/verification-report.json", "artifact_identity": {"commit_sha": self.sha},
            "migration_plan": {"classification": "ADDITIVE", "requires_database_restore": False},
            "deployment_strategy": {"mode": "FAKE"}, "smoke_tests": ["health"],
            "rollback_plan": {"application": "previous SHA", "database": "forward-only"}, "requires_human_approval": True,
            "approved_by": None, "approved_at": None, "scheduled_at": None, "preflight_hash": None,
            "status": "NOT_READY", "created_at": "now", "started_at": None, "completed_at": None, "failure_reason": None,
            "uncertain_outcome": False, "previous_release_sha": None, "deployed_release_sha": None, "evidence_paths": []
        }
        self.contract["release_title_ko"] = "운영 배포 검토 개선"
        self.contract["user_change_summary_ko"] = "운영자가 검증된 변경사항을 한국어로 확인하고 배포 여부를 결정할 수 있습니다."
        atomic_json(self.active, self.contract)
        self.manager = ReleaseManager(self.root, self.active)

    def tearDown(self): self.temp.cleanup()

    def test_preflight_accepts_only_approved_sha_with_linked_verification(self):
        report = self.manager.preflight(self.contract)
        self.assertTrue(report["passed"])
        self.contract["planner_decision"] = "CHANGES_REQUESTED"; atomic_json(self.active, self.contract)
        self.assertFalse(self.manager.preflight(self.contract)["passed"])

    def test_dirty_worktree_and_sha_mismatch_are_blocked(self):
        (self.root / "app.txt").write_text("dirty\n")
        self.assertFalse(self.manager.preflight(self.contract)["passed"])
        self.contract["approved_commit_sha"] = "0" * 40
        self.assertFalse(self.manager.preflight(self.contract, require_clean=False)["passed"])

    def test_untracked_only_worktree_is_blocked(self):
        (self.root / "untracked.txt").write_text("not part of approved SHA\n")
        report = self.manager.preflight(self.contract)
        checks = {item["id"]: item for item in report["checks"]}
        self.assertFalse(checks["clean_worktree"]["passed"])

    def test_exact_release_evidence_is_not_mistaken_for_product_dirtiness(self):
        self.contract["release_id"] = "release-run-1"
        evidence = self.root / "runs" / "run-1" / "planner-review.json"
        atomic_json(evidence, {"verdict": "APPROVED"})
        self.contract["evidence_paths"] = ["runs/run-1/planner-review.json"]
        report = self.manager.preflight(self.contract)
        checks = {item["id"]: item for item in report["checks"]}
        self.assertTrue(checks["clean_worktree"]["passed"])

    def test_clean_dedicated_source_worktree_is_independent_from_operational_root(self):
        with tempfile.TemporaryDirectory() as raw:
            operational = Path(raw)
            source = operational / "source"
            subprocess.run(["git", "clone", "-q", str(self.root), str(source)], check=True)
            (operational / "contracts").mkdir(parents=True)
            (operational / "runs" / "run-1").mkdir(parents=True)
            active = operational / "contracts" / "active-release.json"
            verification = operational / "runs" / "run-1" / "verification-report.json"
            atomic_json(active, self.contract)
            atomic_json(verification, {"commit_sha": self.sha, "commands": [{"command": "test", "status": "PASSED"}]})
            (operational / "dirty-user-file.txt").write_text("preserved\n")
            manager = ReleaseManager(operational, active, source_root=source)
            report = manager.preflight(self.contract)
            checks = {item["id"]: item for item in report["checks"]}
            self.assertTrue(checks["clean_worktree"]["passed"])
            (source / "app.txt").write_text("dirty source\n")
            report = manager.preflight(self.contract)
            checks = {item["id"]: item for item in report["checks"]}
            self.assertFalse(checks["clean_worktree"]["passed"])

    def test_failed_verification_is_blocked(self):
        atomic_json(self.verification, {"commit_sha": self.sha, "commands": [{"command": "test", "status": "FAILED"}]})
        self.assertFalse(self.manager.preflight(self.contract)["passed"])

    def test_secret_scan_and_destructive_migration_are_blocked(self):
        migration = self.root / "backend" / "src" / "main" / "resources" / "db" / "migration" / "V1__bad.sql"
        synthetic_secret = "abcdefgh" + "ijklmnop"
        migration.parent.mkdir(parents=True); migration.write_text(f"DROP TABLE customers;\npassword={synthetic_secret}\n")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True); subprocess.run(["git", "commit", "-qm", "bad"], cwd=self.root, check=True)
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        self.contract["approved_commit_sha"] = sha
        atomic_json(self.verification, {"commit_sha": sha, "commands": [{"command": "test", "status": "PASSED"}]})
        report = self.manager.preflight(self.contract)
        checks = {item["id"]: item for item in report["checks"]}
        self.assertFalse(checks["secret_scan"]["passed"]); self.assertFalse(checks["migration_compatible"]["passed"])

    def test_cli_style_approval_is_sha_bound_and_idempotent_reapproval_rejected(self):
        report = self.manager.preflight(self.contract); self.contract.update({"preflight_hash": report["preflight_hash"], "status": "RELEASE_APPROVAL_REQUIRED"}); atomic_json(self.active, self.contract)
        approved = self.manager.approve("release-1", self.sha, "admin@reviewdr.kr")
        self.assertEqual(approved["approved_commit_sha"], self.sha)
        with self.assertRaises(ReleaseError): self.manager.approve("release-1", self.sha, "admin@reviewdr.kr")
        with self.assertRaises(ReleaseError): self.manager.approve("release-1", "f" * 40, "admin@reviewdr.kr")

    def test_admin_control_plane_approval_is_exactly_sha_and_preflight_bound(self):
        report = self.manager.preflight(self.contract)
        self.contract.update({"preflight_hash": report["preflight_hash"], "control_plane": {"sync_status": "PUBLISHED"}})
        atomic_json(self.active, self.contract)
        remote = {
            "releaseKey": "release-1", "candidateSha": self.sha, "preflightHash": report["preflight_hash"],
            "approvalPreflightHash": report["preflight_hash"], "approvalEnvironment": "PRODUCTION",
            "approvedBy": "admin-id", "approvedAt": "2026-08-27T12:00:00Z", "scheduledAt": None,
            "status": "APPROVAL_REQUIRED",
        }
        class Response:
            def __enter__(self): return self
            def __exit__(self, *_): return None
            def read(self): return json.dumps(remote).encode()
        with patch.dict("os.environ", {"AGENTOWN_RELEASE_CONTROL_URL": "https://control.invalid", "AGENTOWN_RELEASE_AGENT_TOKEN": "test-token"}), patch("urllib.request.urlopen", return_value=Response()):
            synced = self.manager.sync_control_plane_approval()
        self.assertEqual(synced["status"], "APPROVED")
        self.assertEqual(synced["contract"]["approved_by"], "admin-id")
        self.assertEqual(synced["contract"]["status"], "RELEASE_APPROVAL_REQUIRED")
        remote["candidateSha"] = "f" * 40
        with patch.dict("os.environ", {"AGENTOWN_RELEASE_CONTROL_URL": "https://control.invalid", "AGENTOWN_RELEASE_AGENT_TOKEN": "test-token"}), patch("urllib.request.urlopen", return_value=Response()):
            with self.assertRaises(ReleaseError): self.manager.sync_control_plane_approval()

    def test_released_control_plane_state_is_synced_before_stale_preflight_check(self):
        self.contract.update({"preflight_hash": "local-old-hash", "status": "RELEASE_APPROVAL_REQUIRED"})
        atomic_json(self.active, self.contract)
        remote = {
            "releaseKey": "release-1", "candidateSha": self.sha, "preflightHash": "server-release-hash",
            "status": "RELEASED", "actualDeployedSha": self.sha, "uncertainOutcome": False,
            "updatedAt": "2026-08-28T01:00:00Z",
            "detail": {"productionVerification": {
                "observedSha": self.sha, "healthPassed": True, "readinessPassed": True,
                "apiSmokePassed": True, "journeyE2ePassed": True, "migrationPassed": True,
                "errorRateNormal": True, "uncertainOutcome": False,
            }},
        }
        class Response:
            def __enter__(self): return self
            def __exit__(self, *_): return None
            def read(self): return json.dumps(remote).encode()
        environment = {"AGENTOWN_RELEASE_CONTROL_URL": "https://control.invalid", "AGENTOWN_RELEASE_AGENT_TOKEN": "test-token"}
        with patch.dict("os.environ", environment), patch("urllib.request.urlopen", return_value=Response()):
            synced = self.manager.sync_control_plane_approval()
        self.assertEqual(synced["status"], "RELEASED")
        self.assertEqual(synced["contract"]["deployed_release_sha"], self.sha)
        self.assertEqual(synced["contract"]["preflight_hash"], "server-release-hash")
        self.assertEqual(synced["contract"]["control_plane"]["sync_status"], "RELEASED_SYNCED")

        remote["actualDeployedSha"] = "f" * 40
        with patch.dict("os.environ", environment), patch("urllib.request.urlopen", return_value=Response()):
            with self.assertRaises(ReleaseError):
                self.manager.sync_control_plane_approval()

    def test_control_plane_payload_respects_server_text_limits(self):
        self.contract["review_summary"] = "x" * 620
        self.contract["planner_task_id"] = "p" * 340
        self.contract["release_id"] = "r" * 90
        self.contract["deployment_strategy"]["reason"] = "test environment"
        payload = self.manager.control_plane_payload(
            self.contract,
            {"preflight_hash": "hash", "changed_files": []},
        )
        self.assertEqual(payload["userSummary"], self.contract["user_change_summary_ko"])
        self.assertEqual(payload["purpose"], self.contract["release_title_ko"])
        self.assertEqual(len(payload["releaseKey"]), 80)

    def test_control_plane_payload_preserves_batch_task_count(self):
        self.contract.update({
            "release_title_ko": "승인된 개발 세 건",
            "user_change_summary_ko": "검증된 변경 세 건을 한 번에 검토할 수 있습니다.",
            "included_task_count": 3,
        })
        self.contract["deployment_strategy"]["reason"] = "test environment"
        payload = self.manager.control_plane_payload(
            self.contract,
            {"preflight_hash": "hash", "changed_files": []},
        )
        self.assertEqual(payload["includedTaskCount"], 3)

    def test_control_plane_payload_rejects_missing_or_english_user_change_text(self):
        self.contract["user_change_summary_ko"] = "All verification checks passed."
        with self.assertRaises(ReleaseError):
            self.manager.control_plane_payload(self.contract, {"preflight_hash": "hash", "changed_files": []})

    def test_candidate_requires_separate_korean_user_facing_contract(self):
        task = {
            "task_id": "release-copy-fix",
            "evidence_paths": [],
            "release_title_ko": "배포 설명 한국어화",
            "user_change_summary_ko": "운영자가 사용자 변화를 자연스러운 한국어 문장으로 확인할 수 있습니다.",
        }
        candidate = self.manager.create_candidate(
            "run-1", self.sha, "test", task, {"verdict": "APPROVED", "summary": "technical review"},
            "runs/run-1/verification-report.json", None,
        )
        self.assertEqual(candidate["release_title_ko"], task["release_title_ko"])
        self.assertEqual(candidate["user_change_summary_ko"], task["user_change_summary_ko"])
        task.pop("user_change_summary_ko")
        with self.assertRaises(ReleaseError):
            self.manager.create_candidate(
                "run-2", self.sha, "test", task, {"verdict": "APPROVED", "summary": "technical review"},
                "runs/run-1/verification-report.json", None,
            )

    def test_control_plane_candidate_becomes_approvable_only_with_explicit_environment_contract(self):
        report = self.manager.preflight(self.contract)
        self.contract.update({"status": "RELEASE_APPROVAL_REQUIRED", "deployment_strategy": {"mode": "FAKE", "reason": "test"}})
        with patch.dict("os.environ", {"AGENTOWN_RELEASE_ENVIRONMENT_CONFIGURED": "true"}):
            payload = self.manager.control_plane_payload(self.contract, report)
        self.assertEqual(payload["stagingStatus"], "PASSED")
        self.assertTrue(payload["detail"]["environmentContract"]["configured"])
        self.assertEqual(payload["detail"]["screenshotPaths"], [])
        with patch.dict("os.environ", {}, clear=True):
            blocked = self.manager.control_plane_payload(self.contract, report)
        self.assertFalse(blocked["detail"]["environmentContract"]["configured"])

    def test_production_requires_approval_and_revision_smoke_match(self):
        manager = ReleaseManager(self.root, self.active, lambda target, contract: {"exit_code": 0, "observed_sha": contract["approved_commit_sha"], "smoke_passed": True})
        with self.assertRaises(ReleaseError): manager.deploy("production")
        report = manager.preflight(self.contract); self.contract.update({"preflight_hash": report["preflight_hash"], "status": "RELEASE_APPROVAL_REQUIRED", "approved_at": "now", "approval_preflight_hash": report["preflight_hash"]}); atomic_json(self.active, self.contract)
        self.assertTrue(manager.deploy("production")["success"])

    def test_due_scheduled_approval_uses_the_same_revision_verified_path(self):
        manager = ReleaseManager(self.root, self.active, lambda target, contract: {"exit_code": 0, "observed_sha": contract["approved_commit_sha"], "smoke_passed": True})
        report = manager.preflight(self.contract)
        self.contract.update({"preflight_hash": report["preflight_hash"], "status": "SCHEDULED", "approved_at": "now", "scheduled_at": "2026-08-27T00:00:00Z", "approval_preflight_hash": report["preflight_hash"]})
        atomic_json(self.active, self.contract)
        self.assertTrue(manager.deploy("production")["success"])

    def test_uncertain_or_mismatched_revision_is_not_retried_or_successful(self):
        calls = []
        def adapter(target, contract): calls.append(target); return {"exit_code": 0, "observed_sha": "f" * 40, "smoke_passed": True, "uncertain_outcome": True}
        report = self.manager.preflight(self.contract)
        self.contract.update({"status": "RELEASE_APPROVAL_REQUIRED", "approved_at": "now", "preflight_hash": report["preflight_hash"], "approval_preflight_hash": report["preflight_hash"]}); atomic_json(self.active, self.contract)
        result = ReleaseManager(self.root, self.active, adapter).deploy("production")
        self.assertFalse(result["success"]); self.assertTrue(result["contract"]["uncertain_outcome"]); self.assertEqual(calls, ["production"])

    def test_database_restore_never_uses_automatic_app_rollback(self):
        self.contract["migration_plan"]["requires_database_restore"] = True; atomic_json(self.active, self.contract)
        result = self.manager.rollback()
        self.assertEqual(result["status"], "HUMAN_DECISION_REQUIRED")

    def test_atomic_reports_and_log_redaction(self):
        target = self.root / "report.json"; atomic_json(target, {"ok": True})
        self.assertEqual(json.loads(target.read_text()), {"ok": True}); self.assertEqual(list(target.parent.glob("*.tmp")), [])
        synthetic_secret = "abcdefgh" + "ijklmnop"
        self.assertNotIn(synthetic_secret, redact(f"api_key={synthetic_secret}"))

    def test_no_real_adapter_means_staging_and_production_are_blocked(self):
        with self.assertRaises(ReleaseError): self.manager.deploy("staging")
        with self.assertRaises(ReleaseError): self.manager.deploy("production")

    def test_fake_deploy_success_failure_timeout_and_stop(self):
        success = run_fake_deploy_command(["/bin/sh", "-c", f"printf '%s' '{{\"observed_sha\":\"{self.sha}\",\"smoke_passed\":true}}'"], self.sha)
        self.assertEqual(success["exit_code"], 0); self.assertTrue(success["smoke_passed"])
        failure = run_fake_deploy_command(["/bin/sh", "-c", "exit 7"], self.sha)
        self.assertEqual(failure["exit_code"], 7); self.assertFalse(failure["smoke_passed"])
        timeout = run_fake_deploy_command(["/bin/sh", "-c", "sleep 30"], self.sha, timeout_seconds=.05)
        self.assertEqual(timeout["failure"], "TIMEOUT")
        control = self.root / "stop.json"; atomic_json(control, {"command": "STOP"})
        stopped = run_fake_deploy_command(["/bin/sh", "-c", "sleep 30"], self.sha, control_path=control)
        self.assertEqual(stopped["failure"], "STOP")


class SshReleaseAdapterTest(unittest.TestCase):
    def test_adapter_requires_clean_exact_sha_worktree(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw) / "source"
            root.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "release@test.invalid"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "Release Test"], cwd=root, check=True)
            (root / "app.txt").write_text("safe\n")
            subprocess.run(["git", "add", "app.txt"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "safe"], cwd=root, check=True)
            sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            key = Path(raw) / "release.pem"
            key.write_text("test-only\n")
            environment = {
                "AGENTOWN_RELEASE_SSH_KEY": str(key), "AGENTOWN_STAGING_HOST": "staging.invalid",
                "AGENTOWN_PRODUCTION_HOST": "production.invalid",
            }
            with patch.dict("os.environ", environment):
                adapter = SshReleaseAdapter(root, root)
                adapter.validate("staging", sha)
                with self.assertRaises(ReleaseError):
                    adapter.validate("staging", "f" * 40)
                (root / "app.txt").write_text("dirty\n")
                with self.assertRaises(ReleaseError):
                    adapter.validate("staging", sha)


if __name__ == "__main__": unittest.main()
