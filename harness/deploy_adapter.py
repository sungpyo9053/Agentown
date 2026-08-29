#!/usr/bin/env python3
"""SSH release adapter that deploys an immutable Git archive and verifies revision."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable

try:
    from harness.release import ReleaseError
except ModuleNotFoundError:
    from release import ReleaseError


class SshReleaseAdapter:
    def __init__(self, root: Path, source_root: Path, runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run):
        self.root = root
        self.source_root = source_root
        self.runner = runner
        self.user = os.environ.get("AGENTOWN_RELEASE_SSH_USER", "agentown-release")
        self.key = Path(os.environ.get("AGENTOWN_RELEASE_SSH_KEY", ""))
        self.hosts = {
            "staging": os.environ.get("AGENTOWN_STAGING_HOST", ""),
            "production": os.environ.get("AGENTOWN_PRODUCTION_HOST", ""),
            "rollback": os.environ.get("AGENTOWN_PRODUCTION_HOST", ""),
        }

    def _run(self, command: list[str], timeout: int = 5400) -> subprocess.CompletedProcess[str]:
        return self.runner(command, text=True, capture_output=True, timeout=timeout, check=False)

    def _ssh_base(self, host: str) -> list[str]:
        return ["ssh", "-i", str(self.key), "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "StrictHostKeyChecking=accept-new", f"{self.user}@{host}"]

    def validate(self, target: str, sha: str) -> None:
        host = self.hosts.get(target, "")
        if not host or not self.key.is_file():
            raise ReleaseError(f"{target} deployment host or SSH key is not configured")
        status = self._run(["git", "-C", str(self.source_root), "status", "--porcelain=v1", "-uall"], 30)
        head = self._run(["git", "-C", str(self.source_root), "rev-parse", "HEAD"], 30)
        if status.returncode or status.stdout.strip() or head.returncode or head.stdout.strip() != sha:
            raise ReleaseError("release worktree must be clean and checked out at the exact approved SHA")

    def _verify(self, target: str, host: str, sha: str) -> dict[str, Any]:
        project = "agentown-staging" if target == "staging" else "agentown"
        checked = self._run(self._ssh_base(host) + [f"sudo /usr/local/sbin/agentown-release-verify {project}"], 90)
        observed_sha = None
        health_passed = False
        if checked.returncode == 0:
            for line in checked.stdout.splitlines():
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(value, dict) and value.get("commitSha"):
                    observed_sha = value["commitSha"]
                if isinstance(value, dict) and value.get("status") == "UP":
                    health_passed = True
        public_revision = True
        public_health = True
        public_frontend = True
        if target == "production" and checked.returncode == 0:
            base = os.environ.get("AGENTOWN_PRODUCTION_PUBLIC_URL", "").rstrip("/")
            public_revision = public_health = public_frontend = False
            if base:
                try:
                    with urllib.request.urlopen(f"{base}/api/version", timeout=15) as response:
                        public_revision = json.loads(response.read().decode()).get("commitSha") == sha
                    with urllib.request.urlopen(f"{base}/actuator/health", timeout=15) as response:
                        public_health = json.loads(response.read().decode()).get("status") == "UP"
                    with urllib.request.urlopen(f"{base}/login", timeout=15) as response:
                        public_frontend = 200 <= response.status < 400
                except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
                    pass
        smoke_passed = checked.returncode == 0 and health_passed and observed_sha == sha and public_revision and public_health and public_frontend
        return {
            "exit_code": checked.returncode,
            "observed_sha": observed_sha,
            "smoke_passed": smoke_passed,
            "uncertain_outcome": checked.returncode != 0 and observed_sha is None,
            "checks": {"revision": observed_sha == sha, "health": health_passed, "frontend": checked.returncode == 0, "database": checked.returncode == 0, "public_revision": public_revision, "public_health": public_health, "public_frontend": public_frontend},
        }

    def __call__(self, target: str, contract: dict[str, Any]) -> dict[str, Any]:
        sha = contract.get("previous_release_sha") if target == "rollback" else contract["approved_commit_sha"]
        if not sha:
            raise ReleaseError("rollback has no previous release SHA")
        self.validate(target, contract["approved_commit_sha"])
        host = self.hosts[target]
        remote_target = "rollback" if target == "rollback" else target
        with tempfile.TemporaryDirectory(prefix="agentown-release-") as raw:
            temporary = Path(raw)
            archive = temporary / f"{sha}.tar.gz"
            archived = self._run(["git", "-C", str(self.source_root), "archive", "--format=tar.gz", "-o", str(archive), sha], 120)
            if archived.returncode != 0:
                raise ReleaseError("approved Git archive could not be created")
            remote_archive = f"/home/{self.user}/inbox/agentown-{sha}.tar.gz"
            remote_compose = f"/home/{self.user}/inbox/agentown-compose-release-{sha}.yml"
            copies = ((archive, f"inbox/agentown-{sha}.tar.gz"), (self.root / "harness/deploy/docker-compose.release.yml", f"inbox/agentown-compose-release-{sha}.yml"))
            for local, remote in copies:
                copied = self._run(["scp", "-i", str(self.key), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", str(local), f"{self.user}@{host}:{remote}"], 180)
                if copied.returncode != 0:
                    return {"exit_code": copied.returncode, "observed_sha": None, "smoke_passed": False, "uncertain_outcome": False, "failure": "artifact transfer failed"}
            command = self._ssh_base(host) + [f"sudo /usr/local/sbin/agentown-release-deploy {remote_target} {sha} {remote_archive} {remote_compose}"]
            try:
                deployed = self._run(command, 5400)
            except subprocess.TimeoutExpired:
                reconciled = self._verify("staging" if target == "staging" else "production", host, sha)
                reconciled["uncertain_outcome"] = not reconciled["smoke_passed"]
                reconciled["failure"] = "deployment command timed out; revision was reconciled"
                return reconciled
        if deployed.returncode != 0:
            result = self._verify("staging" if target == "staging" else "production", host, sha)
            result["failure"] = "remote deployment failed"
            return result
        return self._verify("staging" if target == "staging" else "production", host, sha)


def configured_release_manager(root: Path):
    try:
        from harness.release import ReleaseManager
    except ModuleNotFoundError:
        from release import ReleaseManager

    source_root = Path(os.environ.get("AGENTOWN_RELEASE_WORKTREE", root))
    adapter = SshReleaseAdapter(root, source_root)
    return ReleaseManager(root, deploy_adapter=adapter, source_root=source_root)
