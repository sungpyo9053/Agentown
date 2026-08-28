#!/usr/bin/env python3
"""Release safety gates. Real deployment requires an explicit configured adapter."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import signal
import time
import tempfile
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[1]
ACTIVE_RELEASE = ROOT / "contracts" / "active-release.json"
RELEASE_LOG = ROOT / "runs" / "release.log"
SECRET_PATTERNS = (
    re.compile(r"(?i)(api[_-]?key|client[_-]?secret|access[_-]?token|password)\s*[:=]\s*['\"]?[A-Za-z0-9_\-/+=]{16,}"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)
DESTRUCTIVE_SQL = re.compile(r"(?im)^\s*(drop\s+(table|column|schema)|truncate\s+|alter\s+table.+drop\s+|delete\s+from\s+\S+\s*;)")
HANGUL = re.compile(r"[가-힣]")


class ReleaseError(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, raw = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = Path(raw)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=root, text=True, capture_output=True)


def hash_json(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def redact(value: str) -> str:
    result = value
    for pattern in SECRET_PATTERNS:
        result = pattern.sub("[REDACTED]", result)
    return result


def bounded_text(value: Any, maximum: int, fallback: str) -> str:
    text = str(value or fallback).strip()
    return text[:maximum]


def korean_release_text(value: Any, field: str, minimum: int, maximum: int) -> str:
    text = str(value or "").strip()
    if len(text) < minimum or len(text) > maximum or len(HANGUL.findall(text)) < 3:
        raise ReleaseError(f"{field} must be a complete Korean user-facing text between {minimum} and {maximum} characters")
    return text


def run_fake_deploy_command(command: list[str], expected_sha: str, timeout_seconds: float = 5, control_path: Path | None = None) -> dict[str, Any]:
    """Test-only process adapter used to prove timeout and pause/stop behavior."""
    process = subprocess.Popen(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic() + timeout_seconds
    interrupted = None
    while process.poll() is None:
        if control_path and control_path.exists():
            interrupted = read_json(control_path).get("command")
        if interrupted in {"PAUSE", "STOP"} or time.monotonic() >= deadline:
            try: os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError: pass
            try: process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
                process.wait()
            if process.stdout: process.stdout.close()
            return {"exit_code": None, "observed_sha": None, "smoke_passed": False, "uncertain_outcome": False, "failure": interrupted or "TIMEOUT"}
        time.sleep(0.02)
    output = (process.stdout.read() if process.stdout else "")[-4000:]
    if process.stdout: process.stdout.close()
    try: result = json.loads(output)
    except json.JSONDecodeError: result = {"observed_sha": None, "smoke_passed": False, "uncertain_outcome": process.returncode == 0}
    return {"exit_code": process.returncode, "observed_sha": result.get("observed_sha"), "smoke_passed": result.get("smoke_passed") is True, "uncertain_outcome": bool(result.get("uncertain_outcome")), "expected_sha": expected_sha}


class ReleaseManager:
    def __init__(self, root: Path = ROOT, active_path: Path | None = None, deploy_adapter: Callable[[str, dict[str, Any]], dict[str, Any]] | None = None):
        self.root = root
        self.active_path = active_path or root / "contracts" / "active-release.json"
        self.deploy_adapter = deploy_adapter

    def load(self) -> dict[str, Any]:
        return read_json(self.active_path)

    def save(self, contract: dict[str, Any]) -> None:
        atomic_json(self.active_path, contract)

    def create_candidate(self, run_id: str, sha: str, branch: str, task: dict[str, Any], review: dict[str, Any], verification_path: str, previous_sha: str | None) -> dict[str, Any]:
        if review.get("verdict") != "APPROVED":
            raise ReleaseError("only a Planner APPROVED task may create a release candidate")
        release_title_ko = korean_release_text(task.get("release_title_ko"), "release_title_ko", 5, 300)
        user_change_summary_ko = korean_release_text(task.get("user_change_summary_ko"), "user_change_summary_ko", 10, 500)
        release_id = f"release-{run_id}"
        evidence = list(dict.fromkeys(task.get("evidence_paths", []) + [verification_path, f"runs/{run_id}/planner-review.json", f"runs/{run_id}/implementation-report.json"]))
        contract = {
            "release_id": release_id, "application": "agentown", "environment": "STAGING", "approved_commit_sha": sha,
            "source_branch": branch, "planner_task_id": task["task_id"], "planner_decision": "APPROVED", "verification_report": verification_path,
            "artifact_identity": {"type": "git-commit", "commit_sha": sha},
            "migration_plan": {"classification": "AUTO_PREFLIGHT", "requires_database_restore": False},
            "deployment_strategy": {"mode": "DRY_RUN_ONLY", "reason": "No isolated staging, revision query, rollback contract, or least-privilege deployment credential is configured."},
            "smoke_tests": ["/api/version SHA equality", "/actuator/health", "frontend login page", "Builder mock E2E"],
            "rollback_plan": {"application": "rebuild explicitly recorded previous SHA", "database": "forward-only migration; recovery requires HUMAN_DECISION_REQUIRED"},
            "requires_human_approval": True, "approved_by": None, "approved_at": None, "scheduled_at": None, "preflight_hash": None,
            "status": "NOT_READY", "created_at": utc_now(), "started_at": None, "completed_at": None,
            "failure_reason": "Actual deployment environment contract is not configured.", "uncertain_outcome": False,
            "previous_release_sha": previous_sha, "deployed_release_sha": None, "evidence_paths": evidence,
            "control_plane": {"approval_source": "ADMIN_UI_ONLY", "admin_email": "admin@reviewdr.kr", "sync_status": "NOT_CONFIGURED"},
            "release_title_ko": release_title_ko,
            "user_change_summary_ko": user_change_summary_ko,
            "included_task_count": int(task.get("included_task_count", 1)),
            "included_task_ids": task.get("included_task_ids", [task["task_id"]]),
            "review_summary": review.get("summary"),
        }
        self.save(contract)
        atomic_json(self.report_dir(release_id) / "release-contract.json", contract)
        summary = f"""# Release {release_id}\n\n- candidate SHA: `{sha}`\n- previous SHA: `{previous_sha or 'unknown'}`\n- Planner task: `{task['task_id']}`\n- Planner verdict: `APPROVED`\n- production deployment: **not performed**\n- control plane: admin@reviewdr.kr UI approval only\n\n## 사용자 변화\n\n{user_change_summary_ko}\n\n## 기술 검증\n\n{review.get('summary', 'See verification report.')}\n\n## Blocker\n\nNo isolated staging, revision query, rollback contract, or least-privilege deployment credential is configured.\n"""
        (self.report_dir(release_id) / "release-summary.md").write_text(summary, encoding="utf-8")
        return contract

    def control_plane_payload(self, contract: dict[str, Any], preflight: dict[str, Any]) -> dict[str, Any]:
        files = preflight.get("changed_files", [])
        migrations = [path for path in files if "/db/migration/" in path]
        release_title_ko = korean_release_text(contract.get("release_title_ko"), "release_title_ko", 5, 300)
        user_change_summary_ko = korean_release_text(contract.get("user_change_summary_ko"), "user_change_summary_ko", 10, 500)
        environment_configured = (
            os.getenv("AGENTOWN_RELEASE_ENVIRONMENT_CONFIGURED", "").lower() == "true"
            and contract.get("deployment_strategy", {}).get("mode") not in {None, "DRY_RUN_ONLY"}
        )
        staging_passed = contract.get("status") == "RELEASE_APPROVAL_REQUIRED"
        return {
            "releaseKey": bounded_text(contract["release_id"], 80, "release"),
            "purpose": release_title_ko,
            "userSummary": user_change_summary_ko,
            "currentSha": contract.get("previous_release_sha"), "candidateSha": contract["approved_commit_sha"],
            "includedTaskCount": int(contract.get("included_task_count", 1)), "riskLevel": "HIGH" if migrations else "MEDIUM", "hasMigration": bool(migrations),
            "stagingStatus": "PASSED" if staging_passed else "NOT_CONFIGURED",
            "preflightHash": preflight["preflight_hash"],
            "detail": {
                "environmentContract": {"configured": environment_configured, "reason": None if environment_configured else contract["deployment_strategy"]["reason"]},
                "userChanges": [user_change_summary_ko],
                "systemChanges": ["Planner 승인 commit만 Release 후보로 취급", "관리자 UI 승인에 SHA와 preflight hash 결속"],
                "technicalChanges": files, "files": files, "plannerDecision": contract["planner_decision"],
                "verificationCommands": [], "stagingResults": {"status": "PASSED" if staging_passed else "NOT_CONFIGURED"},
                "migration": contract["migration_plan"], "risks": ["실제 스테이징과 rollback 계약 미구성"],
                "unverified": ["실제 스테이징 배포", "실제 운영 배포"], "externalImpact": [], "estimatedDowntime": "미확인",
                "rollback": contract["rollback_plan"], "preflight": preflight, "evidencePaths": contract["evidence_paths"],
                "screenshotPaths": ["/release-evidence/release-control-plane.png"],
            },
        }

    def publish_control_plane(self, contract: dict[str, Any], preflight: dict[str, Any]) -> dict[str, Any]:
        base = os.getenv("AGENTOWN_RELEASE_CONTROL_URL", "").rstrip("/")
        token = os.getenv("AGENTOWN_RELEASE_AGENT_TOKEN", "")
        if not base or not token:
            raise ReleaseError("Release Control Plane URL/token is not configured")
        request = urllib.request.Request(f"{base}/api/internal/releases/candidates", data=json.dumps(self.control_plane_payload(contract, preflight)).encode(), method="POST", headers={"Content-Type": "application/json", "X-Release-Agent-Token": token})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return json.loads(response.read().decode())
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise ReleaseError(f"Release Control Plane publish failed without exposing credentials: {type(error).__name__}") from error

    def sync_control_plane_approval(self) -> dict[str, Any]:
        """Import the immutable admin approval for the exact local candidate.

        The admin database is authoritative. A local file can never manufacture an
        approval; it only caches a matching server-side approval for the deploy
        worker that runs at midnight.
        """
        contract = self.load()
        if contract.get("release_id") in {None, "none"}:
            return {"status": "NO_CANDIDATE", "contract": contract}
        base = os.getenv("AGENTOWN_RELEASE_CONTROL_URL", "").rstrip("/")
        token = os.getenv("AGENTOWN_RELEASE_AGENT_TOKEN", "")
        if not base or not token:
            raise ReleaseError("Release Control Plane URL/token is not configured; admin approval cannot be checked")
        request = urllib.request.Request(
            f"{base}/api/internal/releases/{contract['release_id']}",
            headers={"X-Release-Agent-Token": token},
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                remote = json.loads(response.read().decode())
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise ReleaseError(f"Release Control Plane approval sync failed without exposing credentials: {type(error).__name__}") from error
        if remote.get("releaseKey") != contract["release_id"] or remote.get("candidateSha") != contract["approved_commit_sha"]:
            raise ReleaseError("Release Control Plane candidate identity does not match the local release")
        if remote.get("status") == "RELEASED":
            deployed_sha = remote.get("actualDeployedSha")
            if deployed_sha != contract["approved_commit_sha"]:
                raise ReleaseError("Released production SHA does not match the local approved release")
            if remote.get("uncertainOutcome") is True:
                raise ReleaseError("Released production outcome is marked uncertain")
            verification = (remote.get("detail") or {}).get("productionVerification")
            if verification:
                required_checks = (
                    "healthPassed", "readinessPassed", "apiSmokePassed",
                    "journeyE2ePassed", "migrationPassed", "errorRateNormal",
                )
                if verification.get("observedSha") != contract["approved_commit_sha"] or not all(
                    verification.get(check) is True for check in required_checks
                ) or verification.get("uncertainOutcome") is True:
                    raise ReleaseError("Released production verification does not match the local approved release")
            contract.update({
                "status": "RELEASED",
                "deployed_release_sha": deployed_sha,
                "completed_at": remote.get("updatedAt") or now(),
                "uncertain_outcome": False,
                "failure_reason": None,
                "preflight_hash": remote.get("preflightHash") or contract.get("preflight_hash"),
            })
            contract.setdefault("control_plane", {})["sync_status"] = "RELEASED_SYNCED"
            self.save(contract)
            return {"status": "RELEASED", "contract": contract}
        if remote.get("preflightHash") != contract.get("preflight_hash"):
            self._clear_local_approval(contract, "Admin preflight hash differs from the local candidate; approval is stale.")
            raise ReleaseError("Release Control Plane preflight hash does not match the local release")
        approved_at = remote.get("approvedAt")
        approval_hash = remote.get("approvalPreflightHash")
        approved_status = remote.get("status") in {"APPROVAL_REQUIRED", "SCHEDULED"}
        if not approved_at or approval_hash != contract.get("preflight_hash") or not approved_status:
            self._clear_local_approval(contract, "No current immutable admin approval exists for this candidate.")
            return {"status": "NOT_APPROVED", "contract": contract}
        immutable = {
            "release_id": contract["release_id"],
            "approved_commit_sha": contract["approved_commit_sha"],
            "environment": remote.get("approvalEnvironment") or "PRODUCTION",
            "approved_by": remote.get("approvedBy"),
            "approved_at": approved_at,
            "scheduled_at": remote.get("scheduledAt"),
            "approval_preflight_hash": approval_hash,
        }
        existing = self.report_dir(contract["release_id"]) / "approval-record.json"
        if existing.is_file() and read_json(existing) != immutable:
            raise ReleaseError("Immutable local approval record differs from the server approval")
        contract.update(immutable)
        contract["status"] = remote["status"]
        contract.setdefault("control_plane", {})["sync_status"] = "SYNCED"
        contract["failure_reason"] = None
        self.save(contract)
        atomic_json(existing, immutable)
        return {"status": "APPROVED", "contract": contract}

    def _clear_local_approval(self, contract: dict[str, Any], reason: str) -> None:
        contract.update({"approved_by": None, "approved_at": None, "scheduled_at": None, "approval_preflight_hash": None})
        contract.setdefault("control_plane", {})["sync_status"] = "NOT_APPROVED"
        contract["failure_reason"] = reason
        self.save(contract)

    def report_dir(self, release_id: str) -> Path:
        path = self.root / "runs" / release_id / "release"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def changed_files(self, sha: str, previous_sha: str | None) -> list[str]:
        args = ["diff-tree", "--no-commit-id", "--name-only", "-r", sha] if not previous_sha else ["diff", "--name-only", f"{previous_sha}..{sha}"]
        result = git(self.root, *args)
        if result.returncode != 0:
            raise ReleaseError("Unable to resolve approved commit diff")
        return [line for line in result.stdout.splitlines() if line]

    def preflight(self, contract: dict[str, Any], require_clean: bool = True) -> dict[str, Any]:
        checks: list[dict[str, Any]] = []
        sha = contract["approved_commit_sha"]
        exists = git(self.root, "cat-file", "-e", f"{sha}^{{commit}}").returncode == 0
        checks.append({"id": "commit_exists", "passed": exists})
        checks.append({"id": "planner_approved", "passed": contract.get("planner_decision") == "APPROVED"})
        report_path = self.root / contract.get("verification_report", "")
        verification = read_json(report_path) if report_path.is_file() else {}
        commands = verification.get("commands", [])
        verification_ok = bool(commands) and all(item.get("status") == "PASSED" for item in commands)
        checks.append({"id": "verification_passed", "passed": verification_ok})
        linked_sha = verification.get("commit_sha")
        checks.append({"id": "verification_sha_matches", "passed": linked_sha == sha})
        status_lines = git(self.root, "status", "--porcelain=v1", "-uall").stdout.splitlines()
        operational_paths = {
            str(self.active_path.relative_to(self.root)),
            contract.get("verification_report", ""),
        }
        release_id = contract["release_id"]
        run_id = release_id[len("release-"):] if release_id.startswith("release-") else release_id
        for evidence in contract.get("evidence_paths", []):
            if evidence.startswith(f"runs/{run_id}/") and ".." not in Path(evidence).parts:
                operational_paths.add(evidence)
        operational_prefixes = (f"runs/{contract['release_id']}/release/",)
        dirty_paths = []
        for line in status_lines:
            path = line[3:].split(" -> ")[-1] if len(line) > 3 else line
            if path in operational_paths or path.startswith(operational_prefixes):
                continue
            dirty_paths.append(path)
        dirty = bool(dirty_paths)
        checks.append({"id": "clean_worktree", "passed": not dirty if require_clean else True, "observed_dirty": dirty, "dirty_paths": dirty_paths[:100]})
        show = git(self.root, "show", "--format=", "--no-ext-diff", sha)
        secret_ok = show.returncode == 0 and not any(pattern.search(show.stdout) for pattern in SECRET_PATTERNS)
        checks.append({"id": "secret_scan", "passed": secret_ok})
        files = self.changed_files(sha, contract.get("previous_release_sha")) if exists else []
        migrations = [name for name in files if "/db/migration/" in name]
        destructive = []
        for name in migrations:
            content = git(self.root, "show", f"{sha}:{name}")
            if content.returncode == 0 and DESTRUCTIVE_SQL.search(content.stdout):
                destructive.append(name)
        checks.append({"id": "migration_compatible", "passed": not destructive, "migration_files": migrations, "destructive_files": destructive})
        checks.append({"id": "smoke_plan", "passed": bool(contract.get("smoke_tests"))})
        rollback = contract.get("rollback_plan", {})
        checks.append({"id": "rollback_plan", "passed": bool(rollback.get("application")) and bool(rollback.get("database"))})
        real_environment = contract.get("deployment_strategy", {}).get("mode") not in {None, "DRY_RUN_ONLY"}
        checks.append({"id": "deployment_environment", "passed": real_environment, "mode": contract.get("deployment_strategy", {}).get("mode")})
        passed = all(item["passed"] for item in checks)
        report = {"release_id": contract["release_id"], "commit_sha": sha, "checked_at": utc_now(), "passed": passed, "checks": checks, "preflight_hash": hash_json(checks), "changed_files": files}
        atomic_json(self.report_dir(contract["release_id"]) / "preflight-report.json", report)
        return report

    def dry_run(self) -> dict[str, Any]:
        contract = self.load()
        report = self.preflight(contract)
        contract["preflight_hash"] = report["preflight_hash"]
        contract["status"] = "READY_FOR_STAGING" if report["passed"] else "NOT_READY"
        contract["failure_reason"] = None if report["passed"] else "One or more release preflight gates are not proven."
        self.save(contract)
        return {"mutated_external_environment": False, "contract": contract, "preflight": report}

    def approve(self, release_id: str, sha: str, approved_by: str, scheduled_at: str | None = None) -> dict[str, Any]:
        contract = self.load()
        if contract["release_id"] != release_id or contract["approved_commit_sha"] != sha:
            raise ReleaseError("release ID or commit SHA does not match the candidate")
        if contract["status"] != "RELEASE_APPROVAL_REQUIRED":
            raise ReleaseError("release is not waiting for production approval")
        if contract.get("approved_at"):
            raise ReleaseError("release has already been approved")
        report = self.preflight(contract)
        if not report["passed"] or report["preflight_hash"] != contract.get("preflight_hash"):
            raise ReleaseError("preflight changed or failed; approval is invalid")
        contract.update({"approved_by": approved_by, "approved_at": utc_now(), "scheduled_at": scheduled_at, "approval_preflight_hash": report["preflight_hash"]})
        self.save(contract)
        atomic_json(self.report_dir(release_id) / "approval-record.json", {key: contract.get(key) for key in ("release_id", "approved_commit_sha", "environment", "approved_by", "approved_at", "scheduled_at", "approval_preflight_hash")})
        return contract

    def deploy(self, target: str) -> dict[str, Any]:
        contract = self.load()
        if self.deploy_adapter is None:
            raise ReleaseError("no verified deployment adapter is configured")
        if target == "production":
            if not contract.get("approved_at") or contract.get("approval_preflight_hash") != contract.get("preflight_hash"):
                raise ReleaseError("immutable production approval is missing or stale")
            if contract["status"] not in {"RELEASE_APPROVAL_REQUIRED", "SCHEDULED"}:
                raise ReleaseError("release is not production-ready")
            latest = self.preflight(contract)
            if not latest["passed"] or latest["preflight_hash"] != contract.get("approval_preflight_hash"):
                contract.update({"approved_by": None, "approved_at": None, "scheduled_at": None, "status": "HUMAN_DECISION_REQUIRED", "failure_reason": "Scheduled preflight changed or failed; admin approval was invalidated."})
                self.save(contract)
                raise ReleaseError("scheduled preflight changed or failed; production approval was invalidated")
        result = self.deploy_adapter(target, contract)
        observed = result.get("observed_sha")
        uncertain = bool(result.get("uncertain_outcome"))
        success = result.get("exit_code") == 0 and observed == contract["approved_commit_sha"] and result.get("smoke_passed") is True and not uncertain
        contract["uncertain_outcome"] = uncertain
        contract["deployed_release_sha"] = observed
        contract["status"] = "RELEASED" if success and target == "production" else ("RELEASE_APPROVAL_REQUIRED" if success else "FAILED_SAFE")
        contract["failure_reason"] = None if success else "Deployment revision, smoke result, or outcome could not be proven."
        contract["completed_at"] = utc_now()
        self.save(contract)
        atomic_json(self.report_dir(contract["release_id"]) / f"{target}-deployment-report.json", {**result, "success": success, "recorded_at": utc_now()})
        return {"success": success, "contract": contract, "result": result}

    def rollback(self) -> dict[str, Any]:
        contract = self.load()
        if contract.get("migration_plan", {}).get("requires_database_restore"):
            contract["status"] = "HUMAN_DECISION_REQUIRED"
            contract["failure_reason"] = "Database or operating data recovery requires a human decision."
            self.save(contract)
            return contract
        if self.deploy_adapter is None or not contract.get("previous_release_sha"):
            raise ReleaseError("safe application rollback adapter or previous SHA is unavailable")
        result = self.deploy_adapter("rollback", contract)
        contract["status"] = "ROLLED_BACK" if result.get("observed_sha") == contract["previous_release_sha"] and result.get("smoke_passed") else "FAILED_SAFE"
        self.save(contract)
        atomic_json(self.report_dir(contract["release_id"]) / "rollback-report.json", {**result, "recorded_at": utc_now()})
        return contract
