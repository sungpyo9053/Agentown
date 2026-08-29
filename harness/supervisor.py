#!/usr/bin/env python3
"""Bounded, resumable supervisor for independent Codex development cycles."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import plistlib
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "harness" / "config.json"
STATE_PATH = ROOT / "product" / "development-state.json"
TASK_PATH = ROOT / "contracts" / "active-task.json"
LOCK_PATH = ROOT / "product" / ".autonomous-supervisor.lock"
PID_PATH = ROOT / "product" / ".autonomous-supervisor.pid"
CONTROL_PATH = ROOT / "product" / ".autonomous-supervisor.control.json"
LOG_PATH = ROOT / "runs" / "supervisor.log"
BACKGROUND_LABEL = "com.agentown.development-supervisor"
NIGHTLY_LABEL = "com.agentown.nightly-release-first"
LAUNCHD_PLIST_PATH = ROOT / "product" / ".autonomous-supervisor.launchd.plist"
NIGHTLY_PLIST_PATH = ROOT / "product" / ".agentown-nightly.launchd.plist"
PRIVATE_ENV_PATH = Path.home() / ".config" / "agentown" / "release.env"
INFRA_ENV_PATH = Path.home() / ".config" / "agentown" / "release.infrastructure.env"
PRIVATE_ENV_ALLOWLIST = {
    "AGENTOWN_RELEASE_AGENT_TOKEN",
    "AGENTOWN_RELEASE_CONTROL_URL",
    "AGENTOWN_RELEASE_ENVIRONMENT_CONFIGURED",
    "AGENTOWN_RELEASE_DEPLOY_COMMAND",
    "AGENTOWN_RELEASE_WORKTREE",
    "AGENTOWN_RELEASE_SSH_KEY",
    "AGENTOWN_RELEASE_SSH_USER",
    "AGENTOWN_STAGING_HOST",
    "AGENTOWN_PRODUCTION_HOST",
    "AGENTOWN_PRODUCTION_PUBLIC_URL",
}
DEFAULT_BRANCHES = {"main", "master"}
ALLOWED_VERIFY = ("python3 -m unittest tools.tests", "./gradlew", "npm --prefix frontend")
ALLOWED_VERIFY_ENV = {"PLAYWRIGHT_MOCKED_UI": {"true"}}
REQUIRED_EXECUTABLES = ("npm", "node", "java", "git", "codex")
CHECKPOINT_STATUSES = {"STARTING", "PLANNING", "IMPLEMENTING", "VERIFYING", "REVIEWING", "REPORTING", "RETRY_WAIT"}


class SupervisorError(RuntimeError):
    pass


class InfraFailure(SupervisorError):
    pass


class SupervisorInterrupted(SupervisorError):
    def __init__(self, command: str):
        super().__init__(f"supervisor interrupted by {command.lower()} request")
        self.command = command


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + f".{os.getpid()}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def load_private_environment(path: Path = PRIVATE_ENV_PATH) -> list[str]:
    """Load only release-control settings from a private, owner-only env file.

    launchd jobs intentionally do not embed credentials in their plist. Existing
    process environment values win so an operator can make an explicit override.
    """
    if not path.is_file():
        return []
    if path.stat().st_mode & 0o077:
        raise SupervisorError(f"private release environment must be mode 600: {path}")
    loaded: list[str] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            raise SupervisorError(f"invalid private release environment line {line_number}")
        key, value = line.split("=", 1)
        key = key.strip()
        if key not in PRIVATE_ENV_ALLOWLIST:
            raise SupervisorError(f"unsupported private release environment key: {key}")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        if not value:
            raise SupervisorError(f"empty private release environment value: {key}")
        if key not in os.environ:
            os.environ[key] = value
            loaded.append(key)
    return loaded


def config() -> dict[str, Any]:
    base = read_json(CONFIG_PATH)
    env = {
        "max_runtime_seconds": "AGENTOWN_SUPERVISOR_MAX_RUNTIME_SECONDS",
        "max_cycles": "AGENTOWN_SUPERVISOR_MAX_CYCLES",
        "task_timeout_seconds": "AGENTOWN_SUPERVISOR_TASK_TIMEOUT_SECONDS",
        "cycle_wait_seconds": "AGENTOWN_SUPERVISOR_CYCLE_WAIT_SECONDS",
    }
    for key, variable in env.items():
        if os.getenv(variable):
            base[key] = int(os.environ[variable])
    return base


def log(message: str, cfg: dict[str, Any]) -> None:
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    if LOG_PATH.exists() and LOG_PATH.stat().st_size > cfg["max_log_bytes"]:
        rotated = LOG_PATH.with_suffix(".log.1")
        rotated.unlink(missing_ok=True)
        os.replace(LOG_PATH, rotated)
    with LOG_PATH.open("a", encoding="utf-8") as stream:
        stream.write(f"{now()} {message}\n")


def pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def background_command(args: argparse.Namespace) -> list[str]:
    command = [sys.executable, str(Path(__file__).resolve()), "start"]
    if args.max_cycles:
        command += ["--max-cycles", str(args.max_cycles)]
    if args.max_runtime_seconds:
        command += ["--max-runtime-seconds", str(args.max_runtime_seconds)]
    return command


def resolve_required_executables(cfg: dict[str, Any], search_path: str | None = None) -> dict[str, str]:
    commands = {name: name for name in REQUIRED_EXECUTABLES}
    commands["codex"] = str(cfg.get("codex_command", "codex"))
    resolved = {}
    missing = []
    for name, command in commands.items():
        candidate = None
        if os.sep in command:
            path = Path(command).expanduser()
            if path.is_file() and os.access(path, os.X_OK):
                candidate = str(path.absolute())
        else:
            found = shutil.which(command, path=search_path)
            if found:
                candidate = str(Path(found).absolute())
        if candidate:
            resolved[name] = candidate
        else:
            missing.append(f"{name}={command}")
    if missing:
        raise InfraFailure(f"required executable preflight failed: {', '.join(missing)}")
    return resolved


def required_runtime_path(executables: dict[str, str]) -> str:
    paths = []
    for executable in executables.values():
        parent = str(Path(executable).parent)
        if parent not in paths:
            paths.append(parent)
    for fallback in ("/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"):
        if fallback not in paths:
            paths.append(fallback)
    return os.pathsep.join(paths)


def runtime_environment(cfg: dict[str, Any]) -> dict[str, str]:
    if "resolved_executables" not in cfg:
        cfg["resolved_executables"] = resolve_required_executables(cfg)
    environment = os.environ.copy()
    environment["HOME"] = str(Path.home())
    environment["PATH"] = required_runtime_path(cfg["resolved_executables"])
    return environment


def record_infra_failure(error: BaseException) -> dict[str, Any]:
    reason = f"{type(error).__name__}: {error}"
    fingerprint = hashlib.sha256(reason.encode("utf-8")).hexdigest()
    state = read_json(STATE_PATH)
    repeated = state.get("infra_failure_fingerprint") == fingerprint
    state.update({
        "supervisor_status": "FAILED_SAFE",
        "failure_type": "INFRA_FAILURE",
        "infra_failure_reason": reason,
        "infra_failure_fingerprint": fingerprint,
        "consecutive_infra_failures": int(state.get("consecutive_infra_failures", 0)) + 1 if repeated else 1,
        "next_run_allowed": False,
        "supervisor_pid": None,
        "supervisor_exit_reason": "INFRA_FAILURE",
        "supervisor_exited_at": now(),
        "updated_at": now(),
    })
    atomic_json(STATE_PATH, state)
    return state


def start_background(args: argparse.Namespace) -> tuple[int, str]:
    cfg = config()
    try:
        cfg["resolved_executables"] = resolve_required_executables(cfg)
    except InfraFailure as error:
        record_infra_failure(error)
        raise
    command = background_command(args)
    if PID_PATH.exists():
        recorded = int(read_json(PID_PATH).get("pid", 0))
        if recorded and pid_alive(recorded):
            raise SupervisorError(f"supervisor already running pid={recorded}")
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    if sys.platform == "darwin":
        launchd_domain = f"gui/{os.getuid()}"
        subprocess.run(["launchctl", "bootout", f"{launchd_domain}/{BACKGROUND_LABEL}"], cwd=ROOT, text=True, capture_output=True, check=False)
        launchd_config = {
            "Label": BACKGROUND_LABEL,
            "ProgramArguments": command,
            "WorkingDirectory": str(ROOT),
            "StandardOutPath": str(LOG_PATH),
            "StandardErrorPath": str(LOG_PATH),
            "RunAtLoad": True,
            "KeepAlive": False,
            "ProcessType": "Background",
            "EnvironmentVariables": {
                "HOME": str(Path.home()),
                "PATH": required_runtime_path(cfg["resolved_executables"]),
            },
        }
        temporary = LAUNCHD_PLIST_PATH.with_suffix(f".plist.{os.getpid()}.tmp")
        temporary.parent.mkdir(parents=True, exist_ok=True)
        with temporary.open("wb") as stream:
            plistlib.dump(launchd_config, stream, sort_keys=True)
        os.replace(temporary, LAUNCHD_PLIST_PATH)
        submitted = subprocess.run(
            ["launchctl", "bootstrap", launchd_domain, str(LAUNCHD_PLIST_PATH)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        if submitted.returncode != 0:
            detail = submitted.stderr.strip() or submitted.stdout.strip() or "unknown launchctl error"
            raise SupervisorError(f"launchd background supervisor failed to submit: {detail}")
    else:
        with LOG_PATH.open("a", encoding="utf-8") as stream:
            subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if PID_PATH.exists():
            pid = int(read_json(PID_PATH).get("pid", 0))
            if pid and pid_alive(pid):
                return pid, str(LOG_PATH)
        time.sleep(0.25)
    raise SupervisorError(f"background supervisor did not publish a live PID; inspect {LOG_PATH}")


def install_nightly(cfg: dict[str, Any]) -> dict[str, Any]:
    if sys.platform != "darwin":
        raise SupervisorError("nightly installer currently supports launchd on macOS only")
    cfg["resolved_executables"] = resolve_required_executables(cfg)
    domain = f"gui/{os.getuid()}"
    subprocess.run(["launchctl", "bootout", f"{domain}/{NIGHTLY_LABEL}"], cwd=ROOT, capture_output=True, text=True, check=False)
    value = {
        "Label": NIGHTLY_LABEL,
        "ProgramArguments": [sys.executable, str(Path(__file__).resolve()), "nightly", "--max-cycles", "30", "--max-runtime-seconds", "82800"],
        "WorkingDirectory": str(ROOT), "StandardOutPath": str(LOG_PATH), "StandardErrorPath": str(LOG_PATH),
        "StartCalendarInterval": {"Hour": 0, "Minute": 0}, "RunAtLoad": False, "KeepAlive": False,
        "ProcessType": "Background", "EnvironmentVariables": {"HOME": str(Path.home()), "PATH": required_runtime_path(cfg["resolved_executables"])}
    }
    temporary = NIGHTLY_PLIST_PATH.with_suffix(f".plist.{os.getpid()}.tmp")
    with temporary.open("wb") as stream: plistlib.dump(value, stream, sort_keys=True)
    os.replace(temporary, NIGHTLY_PLIST_PATH)
    submitted = subprocess.run(["launchctl", "bootstrap", domain, str(NIGHTLY_PLIST_PATH)], cwd=ROOT, capture_output=True, text=True)
    if submitted.returncode != 0: raise SupervisorError(f"nightly launchd registration failed: {(submitted.stderr or submitted.stdout).strip()}")
    return {"installed": True, "label": NIGHTLY_LABEL, "local_schedule": "00:00", "order": ["approved release preflight/deploy", "revision verification", "development supervisor"], "plist": str(NIGHTLY_PLIST_PATH)}


def nightly_status() -> dict[str, Any]:
    if sys.platform != "darwin": return {"installed": False, "reason": "launchd unavailable"}
    result = subprocess.run(["launchctl", "print", f"gui/{os.getuid()}/{NIGHTLY_LABEL}"], cwd=ROOT, capture_output=True, text=True)
    return {"installed": result.returncode == 0, "label": NIGHTLY_LABEL, "schedule": "00:00 local time", "detail": (result.stdout[-4000:] if result.returncode == 0 else result.stderr.strip())}


class Lock:
    def __init__(self, stale_seconds: int):
        self.stale_seconds = stale_seconds

    def __enter__(self):
        if LOCK_PATH.exists():
            value = read_json(LOCK_PATH)
            pid = int(value.get("pid", 0))
            age = time.time() - float(value.get("acquired_epoch", 0))
            if pid_alive(pid):
                raise SupervisorError(f"supervisor already running pid={pid}")
            LOCK_PATH.unlink()
        try:
            fd = os.open(LOCK_PATH, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.write(fd, json.dumps({"pid": os.getpid(), "acquired_epoch": time.time()}).encode())
            os.close(fd)
        except FileExistsError as error:
            raise SupervisorError("supervisor lock claimed concurrently") from error
        atomic_json(PID_PATH, {"pid": os.getpid(), "started_at": now()})
        return self

    def __exit__(self, *_):
        PID_PATH.unlink(missing_ok=True)
        LOCK_PATH.unlink(missing_ok=True)


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True, check=check)


def ensure_branch(cfg: dict[str, Any], mutate: bool) -> str:
    branch = git("branch", "--show-current").stdout.strip()
    if branch in DEFAULT_BRANCHES:
        if not mutate:
            raise SupervisorError(f"dry-run refuses default branch: {branch}")
        branch = f"{cfg['development_branch_prefix']}-{datetime.now().strftime('%Y%m%d')}"
        if git("show-ref", "--verify", f"refs/heads/{branch}", check=False).returncode == 0:
            git("switch", branch)
        else:
            git("switch", "-c", branch)
    return branch


def auth_status(cfg: dict[str, Any]) -> str:
    command = cfg.get("resolved_executables", {}).get("codex", cfg["codex_command"])
    try:
        result = subprocess.run([command, "login", "status"], cwd=ROOT, text=True, capture_output=True, timeout=30, env=runtime_environment(cfg))
    except FileNotFoundError as error:
        raise InfraFailure(f"Codex CLI could not start: {error}") from error
    if result.returncode != 0:
        raise SupervisorError("Codex CLI authentication is unavailable")
    return result.stdout.strip() or result.stderr.strip() or "authenticated"


def control() -> str:
    if not CONTROL_PATH.exists():
        return "RUN"
    return read_json(CONTROL_PATH).get("command", "RUN")


def terminate_process_group(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def set_state(**updates: Any) -> dict[str, Any]:
    state = read_json(STATE_PATH)
    state.update(updates)
    state["updated_at"] = now()
    atomic_json(STATE_PATH, state)
    return state


def checkpoint_resume_reason(state: dict[str, Any]) -> str | None:
    status = state.get("supervisor_status")
    active = state.get("current_active_task")
    checkpoint_status = state.get("checkpoint_status")
    if active and status == "READY_FOR_NEXT_CYCLE" and checkpoint_status in CHECKPOINT_STATUSES:
        return f"CONTROL_PAUSE_{checkpoint_status}_CHECKPOINT"
    if active and status == "READY_FOR_NEXT_CYCLE" and state.get("supervisor_exit_reason") == "CONTROL_PAUSE":
        return "CONTROL_PAUSE_CHECKPOINT"
    if not active or status not in CHECKPOINT_STATUSES:
        return None
    previous_pid = state.get("supervisor_pid")
    if previous_pid and not pid_alive(int(previous_pid)):
        return "SUPERVISOR_PROCESS_LOST"
    if status == "RETRY_WAIT" and not previous_pid:
        return "RETRY_WAIT_PROCESS_MISSING"
    return None


def retry_backoff_seconds(cfg: dict[str, Any], task: dict[str, Any]) -> int:
    base = max(1, int(cfg["cycle_wait_seconds"]))
    exponent = max(0, int(task.get("iteration", 1)) - 2)
    return min(int(cfg.get("max_retry_backoff_seconds", 300)), base * (2 ** exponent))


def changes_requested_retry_allowed(task: dict[str, Any]) -> bool:
    return int(task.get("iteration", 1)) < int(task.get("max_iterations", 1))


def failure_limit_reached(failures: int, cfg: dict[str, Any]) -> bool:
    return failures >= int(cfg["max_consecutive_failures"])


def terminal_task_status(verdict: str) -> str:
    if verdict == "HUMAN_DECISION_REQUIRED":
        return "HUMAN_DECISION_REQUIRED"
    if verdict == "BLOCKED":
        return "BLOCKED"
    return "BLOCKED"


def validate_task_contract(task: dict[str, Any]) -> None:
    commit_scope = task.get("commit_scope")
    if not isinstance(commit_scope, list) or not commit_scope:
        raise SupervisorError("Planner task has no commit_scope")
    if len(commit_scope) != len(set(commit_scope)):
        raise SupervisorError("Planner task commit_scope contains duplicates")
    for raw in commit_scope:
        path = Path(str(raw))
        if path.is_absolute() or ".." in path.parts:
            raise SupervisorError(f"Planner task contains unsafe commit scope: {raw}")


def parse_verification_command(raw: str) -> tuple[list[str], dict[str, str]]:
    """Parse a verifier command without invoking a shell.

    A tiny allowlist supports explicit test-mode flags while rejecting arbitrary
    environment injection, pipes, redirects, and external commands.
    """
    tokens = shlex.split(raw)
    environment: dict[str, str] = {}
    while tokens and "=" in tokens[0]:
        key, value = tokens.pop(0).split("=", 1)
        if value not in ALLOWED_VERIFY_ENV.get(key, set()):
            raise SupervisorError("Autonomous task contains unsupported verification environment")
        environment[key] = value
    if not tokens or not " ".join(tokens).startswith(ALLOWED_VERIFY):
        raise SupervisorError("Autonomous task contains live or unsupported verification; defer it as a separate human-gated task")
    return tokens, environment


def validate_bounded_task_contract(task: dict[str, Any], cfg: dict[str, Any]) -> None:
    validate_task_contract(task)
    limits = {
        "acceptance_criteria": int(cfg.get("max_acceptance_criteria_per_task", 6)),
        "verification_required": int(cfg.get("max_verification_commands_per_task", 6)),
        "commit_scope": int(cfg.get("max_commit_scope_paths_per_task", 12)),
    }
    for field, limit in limits.items():
        values = task.get(field, [])
        if not isinstance(values, list) or len(values) > limit:
            raise SupervisorError(f"Planner task {field} exceeds bounded task limit {limit}")
    for command in task.get("verification_required", []):
        parse_verification_command(command)


def verification_failure_type(output: str, exit_code: int | None) -> str:
    lowered = output.lower()
    infra_markers = (
        "cannot connect to the docker daemon",
        "could not find a valid docker environment",
        "address already in use",
        "connection refused",
        "command not found",
        "operation not permitted",
    )
    fixture_markers = (
        "value too long for type character varying",
        "fixture",
        "test data",
    )
    if exit_code == 127 or any(marker in lowered for marker in infra_markers):
        return "TEST_INFRA_FAILURE"
    if any(marker in lowered for marker in fixture_markers):
        return "TEST_FIXTURE_FAILURE"
    return "PRODUCT_OR_TEST_FAILURE"


def verification_fingerprint(results: list[dict[str, Any]]) -> str | None:
    failed = [
        {"command": item.get("command"), "status": item.get("status"), "failure_type": item.get("failure_type")}
        for item in results if item.get("status") != "PASSED"
    ]
    if not failed:
        return None
    return hashlib.sha256(json.dumps(failed, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def review_failure_fingerprint(review: dict[str, Any], verification: dict[str, Any]) -> str | None:
    if review.get("verdict") == "APPROVED":
        return None
    criteria = sorted(
        (item.get("id"), item.get("status"))
        for item in review.get("criteria", [])
        if item.get("status") != "PASSED"
    )
    commands = sorted(
        (item.get("command"), item.get("status"), item.get("failure_type"))
        for item in verification.get("commands", [])
        if item.get("status") != "PASSED"
    )
    payload = {"verdict": review.get("verdict"), "criteria": criteria, "commands": commands}
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def require_complete_verification(task: dict[str, Any], verification: dict[str, Any]) -> None:
    required_commands = task.get("verification_required", [])
    observed_commands = verification.get("commands", [])
    observed_by_command = {item.get("command"): item for item in observed_commands}
    missing_commands = [command for command in required_commands if command not in observed_by_command]
    failed_commands = [command for command in required_commands if observed_by_command.get(command, {}).get("status") != "PASSED"]
    if not required_commands or missing_commands or failed_commands or len(observed_commands) != len(required_commands):
        raise SupervisorError("Planner approved before every required verification command passed")


def run_codex(role: str, prompt: str, output: Path, run_log: Path, cfg: dict[str, Any], timeout: int, schema: Path | None = None, writable: bool = False) -> None:
    command = [cfg.get("resolved_executables", {}).get("codex", cfg["codex_command"]), "--ask-for-approval", "never", "exec", "--json", "--color", "never", "-C", str(ROOT), "--sandbox", "workspace-write" if writable else "read-only", "-o", str(output)]
    if schema:
        command += ["--output-schema", str(schema)]
    command += ["-"]
    log(f"codex {role} started", cfg)
    with run_log.open("a", encoding="utf-8") as stream:
        try:
            process = subprocess.Popen(command, cwd=ROOT, text=True, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True, env=runtime_environment(cfg))
        except FileNotFoundError as error:
            raise InfraFailure(f"{role} executable could not start: {error}") from error
        assert process.stdin is not None
        process.stdin.write(prompt)
        process.stdin.close()
        deadline = time.monotonic() + timeout
        while process.poll() is None:
            requested = control()
            if requested in {"PAUSE", "STOP"}:
                terminate_process_group(process)
                raise SupervisorInterrupted(requested)
            if time.monotonic() >= deadline:
                terminate_process_group(process)
                raise SupervisorError(f"{role} timed out")
            time.sleep(1)
    if process.returncode != 0:
        recent_output = run_log.read_text(encoding="utf-8")[-4000:] if run_log.exists() else ""
        if "invalid_json_schema" in recent_output:
            raise InfraFailure(f"{role} response schema is rejected by the Codex API")
        if process.returncode == 127:
            raise InfraFailure(f"{role} executable dependency was not found")
        raise SupervisorError(f"{role} Codex call failed with exit {process.returncode}")
    log(f"codex {role} completed", cfg)


def verify(task: dict[str, Any], run_dir: Path, cfg: dict[str, Any]) -> dict[str, Any]:
    results = []
    deadline = time.monotonic() + cfg["verification_timeout_seconds"]
    for raw in task["verification_required"]:
        try:
            argv, environment_overrides = parse_verification_command(raw)
        except SupervisorError:
            results.append({"command": raw, "status": "UNVERIFIED", "exit_code": None, "output": "Command is outside the verifier allowlist or requires live human authorization."})
            continue
        remaining = max(1, int(deadline - time.monotonic()))
        try:
            with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as stream:
                command_environment = runtime_environment(cfg)
                command_environment.update(environment_overrides)
                process = subprocess.Popen(
                    argv, cwd=ROOT, text=True, stdout=stream,
                    stderr=subprocess.STDOUT, start_new_session=True, env=command_environment,
                )
                command_deadline = time.monotonic() + remaining
                while process.poll() is None:
                    requested = control()
                    if requested in {"PAUSE", "STOP"}:
                        terminate_process_group(process)
                        raise SupervisorInterrupted(requested)
                    if time.monotonic() >= command_deadline:
                        terminate_process_group(process)
                        results.append({"command": raw, "status": "FAILED", "exit_code": None, "output": "verification timeout"})
                        break
                    time.sleep(1)
                else:
                    stream.seek(0)
                    output = stream.read()[-12000:]
                    status = "PASSED" if process.returncode == 0 else "FAILED"
                    if process.returncode == 127 or "command not found" in output.lower():
                        status = "INFRA_FAILURE"
                    failure_type = None if status == "PASSED" else verification_failure_type(output, process.returncode)
                    results.append({"command": raw, "status": status, "exit_code": process.returncode, "failure_type": failure_type, "output": output})
                    if status == "INFRA_FAILURE":
                        break
                if results and results[-1]["output"] == "verification timeout":
                    break
        except FileNotFoundError as error:
            results.append({"command": raw, "status": "INFRA_FAILURE", "exit_code": None, "output": f"verification command could not start: {error}"})
            break
        except OSError as error:
            results.append({"command": raw, "status": "INFRA_FAILURE", "exit_code": None, "output": f"verification infrastructure error: {error}"})
            break
    infra_failures = [item for item in results if item["status"] == "INFRA_FAILURE"]
    report_status = "INFRA_FAILURE" if infra_failures else "VERIFYING"
    report = {"run_id": run_dir.name, "status": report_status, "commands": results, "criteria": [], "live_evidence": [], "mock_evidence": [], "failures": [item for item in results if item["status"] == "FAILED"], "infra_failures": infra_failures, "failure_fingerprint": verification_fingerprint(results), "planner_verdict": "PENDING"}
    atomic_json(run_dir / "verification-report.json", report)
    return report


def planner_review_prompt(task: dict[str, Any], verification: dict[str, Any]) -> str:
    return f"""Read agents/product-planner.md and inspect the actual repository diff. Review only the active contract below.
Do not edit files. Mocks are not live evidence. Every acceptance criterion without observed evidence is UNVERIFIED.
Return JSON matching contracts/planner-review.schema.json.

TASK:\n{json.dumps(task, ensure_ascii=False)}
VERIFICATION:\n{json.dumps(verification, ensure_ascii=False)}
"""


def planner_task_prompt(state: dict[str, Any]) -> str:
    return f"""Read agents/product-planner.md, product/vision.md, product/product-principles.md, product/service-readiness.md, product/development-state.json, prior run evidence, and the actual repository.
Do not edit files. Select exactly one highest-impact safe service blocker. Do not choose deployment, OAuth authorization, payments, secrets, destructive migration, or a product decision requiring a human.
Keep the task small enough for one 30-60 minute implementation: at most 6 acceptance criteria, 6 local automated verification commands, and 12 exact repository-relative commit_scope paths. Do not include live, production, OAuth, or manually observed verification in an autonomous task; record that as a separate deferred human gate.
Return a new task JSON matching contracts/task.schema.json with status DISCOVERED, iteration 1, max_iterations at most 3, explicit evidence paths, an exact repository-relative commit_scope, and verification commands from the repository.
Always provide `release_title_ko` and `user_change_summary_ko` as natural Korean text for non-technical users. Do not copy the technical review summary into either field.

CURRENT STATE:\n{json.dumps(state, ensure_ascii=False)}
"""


def developer_prompt(task: dict[str, Any], run_dir: Path) -> str:
    return f"""Read agents/developer.md, product/vision.md, product/product-principles.md, product/development-state.json and the active task below.
Implement only this task. Preserve all pre-existing unrelated dirty files. Do not commit, push, deploy, enter secrets, authorize OAuth, or weaken tests.
Do not change Gradle, Node, package-manager, or toolchain configuration to work around Codex sandbox restrictions; the external verifier runs outside your sandbox.
Inspect current partial changes first and complete or repair them. Write a truthful structured report to {run_dir / 'implementation-report.json'}.
The report must include a non-empty `changed_files` array containing only repository-relative files attributable to this task. `source_files` may be included as additional context, but does not replace `changed_files`.

ACTIVE TASK:\n{json.dumps(task, ensure_ascii=False)}
"""


def notion_fallback(run_dir: Path, task: dict[str, Any], review: dict[str, Any], commit_sha: str | None) -> None:
    text = f"""# Agentown autonomous development report

- run_id: `{run_dir.name}`
- recorded_at: `{now()}`
- task: `{task['task_id']}`
- verdict: `{review['verdict']}`
- commit: `{commit_sha or 'none'}`
- Notion sync: blocked; no authenticated semantic writer is configured for this supervisor.

## Summary

{review['summary']}

## Human decision

{review.get('human_decision') or 'none'}
"""
    (run_dir / "notion-report.md").write_text(text, encoding="utf-8")


def commit_cycle(task: dict[str, Any], run_dir: Path, cfg: dict[str, Any]) -> str:
    status = git("status", "--porcelain=v1", "-uall").stdout.splitlines()
    paths = [line[3:] for line in status if len(line) > 3 and " -> " not in line[3:]]
    staged_before = git("diff", "--cached", "--name-only").stdout.splitlines()
    if staged_before:
        raise SupervisorError("refusing to mix pre-staged changes into an autonomous task commit")
    report_path = run_dir / "implementation-report.json"
    if not report_path.exists():
        raise SupervisorError("approved cycle has no implementation report for task-scoped commit")
    report = read_json(report_path)
    declared = report.get("changed_files")
    if declared is None:
        # Compatibility for reports produced before changed_files became an
        # explicit contract field. The normal scope/diff/protection checks below
        # still decide whether any listed source file is attributable.
        declared = report.get("source_files", [])
    if not isinstance(declared, list) or not declared:
        raise SupervisorError("implementation report has no changed_files or compatible source_files for task-scoped commit")
    declared_paths = set()
    for raw in declared:
        path = Path(str(raw))
        if path.is_absolute() or ".." in path.parts:
            raise SupervisorError(f"implementation report contains unsafe path: {raw}")
        declared_paths.add(path.as_posix())
    commit_scope = task.get("commit_scope", [])
    if not isinstance(commit_scope, list) or not commit_scope:
        raise SupervisorError("task contract has no commit_scope")
    normalized_scope = []
    for raw in commit_scope:
        path = Path(str(raw).rstrip("/"))
        if path.is_absolute() or ".." in path.parts:
            raise SupervisorError(f"task contract contains unsafe commit scope: {raw}")
        normalized_scope.append(str(raw))

    def in_contract_scope(path: str) -> bool:
        return any(path == scope.rstrip("/") or (scope.endswith("/") and path.startswith(scope)) for scope in normalized_scope)

    operational_evidence = {
        (run_dir / name).relative_to(ROOT).as_posix()
        for name in (
            "implementation-report.json",
            "verification-report.json",
            "planner-review.json",
            "notion-report.md",
        )
    }
    declared_product_paths = declared_paths - operational_evidence
    dirty_declared_outside_scope = sorted(path for path in paths if path in declared_product_paths and not in_contract_scope(path))
    if dirty_declared_outside_scope:
        raise SupervisorError(f"implementation report declares dirty files outside task commit_scope: {', '.join(dirty_declared_outside_scope)}")
    protected = set(cfg.get("protected_paths", []))
    prefixes = tuple(cfg.get("protected_prefixes", []))
    suffixes = tuple(cfg.get("protected_suffixes", []))
    attributable = sorted(path for path in paths if path in declared_product_paths and in_contract_scope(path) and path not in protected and not path.startswith(prefixes) and not path.endswith(suffixes))
    if attributable:
        git("add", "--", *attributable)
    staged = git("diff", "--cached", "--name-only").stdout.splitlines()
    forbidden = {path for path in staged if path in protected or path.startswith(prefixes) or path.endswith(suffixes)}
    if forbidden:
        git("restore", "--staged", "--", *sorted(forbidden))
        raise SupervisorError("protected user files entered the staged diff")
    if not staged:
        raise SupervisorError("approved cycle produced no attributable staged changes")
    unattributed = set(staged) - set(attributable)
    if unattributed:
        git("restore", "--staged", "--", *sorted(unattributed))
        raise SupervisorError("task commit contained files absent from the implementation report")
    git("commit", "-m", f"feat: {task['task_id']}")
    return git("rev-parse", "HEAD").stdout.strip()


def create_release_candidate(task: dict[str, Any], review: dict[str, Any], run_dir: Path, commit_sha: str, branch: str) -> dict[str, Any]:
    try:
        from harness.release import ReleaseManager
    except ModuleNotFoundError:
        from release import ReleaseManager
    verification_path = run_dir / "verification-report.json"
    verification = read_json(verification_path)
    verification["commit_sha"] = commit_sha
    atomic_json(verification_path, verification)
    previous = git("rev-parse", f"{commit_sha}^").stdout.strip()
    try:
        from harness.deploy_adapter import configured_release_manager
    except ModuleNotFoundError:
        from deploy_adapter import configured_release_manager
    manager = configured_release_manager(ROOT)
    contract = manager.create_candidate(run_dir.name, commit_sha, branch, task, review, str(verification_path.relative_to(ROOT)), previous)
    preflight = manager.preflight(contract)
    contract["preflight_hash"] = preflight["preflight_hash"]
    manager.save(contract)
    try:
        manager.publish_control_plane(contract, preflight)
        contract.setdefault("control_plane", {})["sync_status"] = "PUBLISHED"
    except Exception as error:
        contract.setdefault("control_plane", {})["sync_status"] = "NOT_CONFIGURED" if "not configured" in str(error) else "PUBLISH_FAILED"
        contract["failure_reason"] = str(error)
    manager.save(contract)
    set_state(
        current_release_id=contract["release_id"], release_candidate_sha=commit_sha,
        staging_status="NOT_CONFIGURED", production_approval_status="NOT_APPROVED",
        deployment_failure_count=0, rollback_status="NOT_REQUIRED",
        release_blockers=[contract["failure_reason"]], next_release_allowed=False,
    )
    return contract


class Supervisor:
    def __init__(self, cfg: dict[str, Any], max_cycles: int | None = None, max_runtime: int | None = None):
        self.cfg = cfg
        self.max_cycles = max_cycles or cfg["max_cycles"]
        self.max_runtime = max_runtime or cfg["max_runtime_seconds"]
        self.stop_requested = False

    def request_stop(self, *_):
        self.stop_requested = True
        latest = read_json(STATE_PATH)
        set_state(
            supervisor_status="PAUSING",
            checkpoint_status=latest.get("supervisor_status"),
            checkpoint_run_id=latest.get("last_run_id"),
            next_run_allowed=False,
            stop_requested_at=now(),
        )

    def wait(self, seconds: int) -> None:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline and not self.stop_requested:
            if control() in {"PAUSE", "STOP"}: return
            time.sleep(min(1, max(0, deadline - time.monotonic())))

    def run(self, dry_run: bool = False) -> dict[str, Any]:
        try:
            self.cfg["resolved_executables"] = resolve_required_executables(self.cfg)
        except InfraFailure as error:
            if dry_run:
                raise
            log(f"infrastructure preflight failed: {error}", self.cfg)
            return record_infra_failure(error)
        try:
            branch = ensure_branch(self.cfg, mutate=not dry_run)
            auth = auth_status(self.cfg)
        except FileNotFoundError as error:
            failure = InfraFailure(f"infrastructure command could not start: {error}")
            if dry_run:
                raise failure from error
            log(f"infrastructure preflight failed: {failure}", self.cfg)
            return record_infra_failure(failure)
        except InfraFailure as error:
            if dry_run:
                raise
            log(f"infrastructure preflight failed: {error}", self.cfg)
            return record_infra_failure(error)
        if dry_run:
            task = read_json(TASK_PATH)
            calls = ["developer", "verifier", "planner-review"]
            if task.get("status") in {"APPROVED", "BLOCKED", "DEFERRED", "HUMAN_DECISION_REQUIRED"}:
                calls.insert(0, "planner")
            return {
                "valid": True,
                "branch": branch,
                "auth": auth,
                "task": task["task_id"],
                "would_call": calls,
                "resolved_executables": self.cfg["resolved_executables"],
                "runtime_path": required_runtime_path(self.cfg["resolved_executables"]),
                "mutated": False,
            }
        with Lock(self.cfg["stale_lock_seconds"]):
            signal.signal(signal.SIGTERM, self.request_stop)
            signal.signal(signal.SIGINT, self.request_stop)
            started = time.monotonic()
            previous = read_json(STATE_PATH)
            resume_reason = checkpoint_resume_reason(previous)
            if resume_reason:
                log(f"supervisor resumed from checkpoint: {resume_reason} task={previous.get('current_active_task')}", self.cfg)
            state = set_state(
                supervisor_status="STARTING",
                supervisor_started_at=now(),
                supervisor_pid=os.getpid(),
                next_run_allowed=True,
                resumed_from_checkpoint=bool(resume_reason),
                resume_reason=resume_reason,
                previous_supervisor_pid=previous.get("supervisor_pid"),
                supervisor_exit_reason=None,
                supervisor_exited_at=None,
                failure_type=None,
                infra_failure_reason=None,
                checkpoint_status=None,
                checkpoint_run_id=None,
            )
            completed = 0
            exit_reason = "LOOP_COMPLETED"
            while completed < self.max_cycles and time.monotonic() - started < self.max_runtime and not self.stop_requested:
                command = control()
                if command in {"PAUSE", "STOP"}:
                    latest = read_json(STATE_PATH)
                    state = set_state(
                        supervisor_status="PAUSED" if command == "PAUSE" else "COMPLETED",
                        checkpoint_status=latest.get("supervisor_status"),
                        checkpoint_run_id=latest.get("last_run_id"),
                        next_run_allowed=command != "STOP",
                    )
                    exit_reason = f"CONTROL_{command}"
                    break
                run_id = f"{datetime.now().strftime('%Y%m%dT%H%M%S')}-supervisor-{completed + 1:02d}"
                run_dir = ROOT / "runs" / run_id
                run_dir.mkdir(parents=True, exist_ok=False)
                task = read_json(TASK_PATH)
                if task.get("status") in {"APPROVED", "BLOCKED", "DEFERRED", "HUMAN_DECISION_REQUIRED"}:
                    try:
                        set_state(supervisor_status="PLANNING", last_run_id=run_id)
                        planned_path = run_dir / "planner-task.json"
                        remaining = max(1, int(self.max_runtime - (time.monotonic() - started)))
                        run_codex("planner", planner_task_prompt(read_json(STATE_PATH)), planned_path, run_dir / "planner.jsonl", self.cfg, min(self.cfg["task_timeout_seconds"], remaining), schema=ROOT / "contracts" / "task.schema.json")
                        task = read_json(planned_path)
                        if task.get("status") != "DISCOVERED" or task.get("iteration") != 1:
                            raise SupervisorError("Planner returned an invalid initial task state")
                        validate_bounded_task_contract(task, self.cfg)
                        task["status"] = "IN_PROGRESS"
                        atomic_json(TASK_PATH, task)
                    except SupervisorInterrupted as interrupted:
                        latest = read_json(STATE_PATH)
                        set_state(
                            supervisor_status="PAUSED" if interrupted.command == "PAUSE" else "COMPLETED",
                            checkpoint_status=latest.get("supervisor_status"),
                            checkpoint_run_id=run_id,
                            next_run_allowed=interrupted.command == "PAUSE",
                        )
                        log(str(interrupted), self.cfg)
                        exit_reason = f"CONTROL_{interrupted.command}"
                        break
                    except InfraFailure as error:
                        record_infra_failure(error)
                        log(f"planner infrastructure failure: {error}", self.cfg)
                        exit_reason = "INFRA_FAILURE"
                        break
                    except Exception as error:
                        set_state(
                            supervisor_status="FAILED_SAFE",
                            failure_type="PLANNER_FAILURE",
                            planner_failure_reason=f"{type(error).__name__}: {error}",
                            next_run_allowed=False,
                        )
                        log(f"planner failed safely: {type(error).__name__}: {error}", self.cfg)
                        exit_reason = "PLANNER_FAILURE"
                        break
                atomic_json(run_dir / "planner-task.json", task)
                current_state = read_json(STATE_PATH)
                set_state(supervisor_status="IMPLEMENTING", current_active_task=task["task_id"], last_run_id=run_id, cumulative_cycles=current_state.get("cumulative_cycles", 0) + 1)
                try:
                    if task.get("status") not in {"IMPLEMENTED", "VERIFYING"}:
                        remaining = max(1, int(self.max_runtime - (time.monotonic() - started)))
                        run_codex("developer", developer_prompt(task, run_dir), run_dir / "developer-last-message.txt", run_dir / "developer.jsonl", self.cfg, min(self.cfg["task_timeout_seconds"], remaining), writable=True)
                        task["status"] = "IMPLEMENTED"
                        atomic_json(TASK_PATH, task)
                    else:
                        log(f"developer checkpoint reused for {task['task_id']}", self.cfg)
                    set_state(supervisor_status="VERIFYING")
                    verification = verify(task, run_dir, self.cfg)
                    if verification.get("status") == "INFRA_FAILURE":
                        detail = verification.get("infra_failures", [{}])[0].get("output", "verification infrastructure failure")
                        state = record_infra_failure(InfraFailure(detail))
                        log(f"verification infrastructure failure: {detail}", self.cfg)
                        exit_reason = "INFRA_FAILURE"
                        break
                    task["status"] = "VERIFYING"
                    atomic_json(TASK_PATH, task)
                    set_state(supervisor_status="REVIEWING")
                    review_path = run_dir / "planner-review.json"
                    remaining = max(1, int(self.max_runtime - (time.monotonic() - started)))
                    run_codex("planner-review", planner_review_prompt(task, verification), review_path, run_dir / "planner-review.jsonl", self.cfg, min(self.cfg["task_timeout_seconds"], remaining), schema=ROOT / "contracts" / "planner-review.schema.json")
                    review = read_json(review_path)
                    commit_sha = None
                    if review["verdict"] == "APPROVED":
                        require_complete_verification(task, verification)
                        set_state(supervisor_status="COMMITTING")
                        task["status"] = "APPROVED"
                        atomic_json(TASK_PATH, task)
                        commit_sha = commit_cycle(task, run_dir, self.cfg)
                        if git("cat-file", "-e", f"{commit_sha}^{{commit}}", check=False).returncode == 0:
                            set_state(supervisor_status="RELEASE_PREFLIGHT")
                            create_release_candidate(task, review, run_dir, commit_sha, branch)
                        completed_tasks = read_json(STATE_PATH).get("completed_tasks", [])
                        if task["task_id"] not in completed_tasks: completed_tasks.append(task["task_id"])
                        state = set_state(last_success_commit_sha=commit_sha, consecutive_failures=0, completed_tasks=completed_tasks, current_active_task=None)
                        post_report_status = "READY_FOR_NEXT_CYCLE"
                        retry_delay = None
                    elif review["verdict"] == "CHANGES_REQUESTED" and changes_requested_retry_allowed(task):
                        task["iteration"] += 1; task["status"] = "CHANGES_REQUESTED"; atomic_json(TASK_PATH, task)
                        retry_delay = retry_backoff_seconds(self.cfg, task)
                        retry_resume_at = (datetime.now(timezone.utc) + timedelta(seconds=retry_delay)).isoformat()
                        state = set_state(
                            supervisor_status="RETRY_WAIT",
                            next_run_allowed=True,
                            retry_backoff_seconds=retry_delay,
                            retry_resume_at=retry_resume_at,
                        )
                        post_report_status = "RETRY_WAIT"
                    else:
                        status = "HUMAN_DECISION_REQUIRED" if review["verdict"] == "HUMAN_DECISION_REQUIRED" else "FAILED_SAFE"
                        task["status"] = terminal_task_status(review["verdict"])
                        atomic_json(TASK_PATH, task)
                        latest = read_json(STATE_PATH)
                        blocked_tasks = latest.get("blocked_tasks", [])
                        if task["status"] == "BLOCKED" and task["task_id"] not in blocked_tasks:
                            blocked_tasks.append(task["task_id"])
                        state = set_state(
                            supervisor_status=status,
                            next_run_allowed=False,
                            blocked_tasks=blocked_tasks,
                            human_decisions_required=latest.get("human_decisions_required", []) + ([review.get("human_decision")] if review.get("human_decision") else []),
                        )
                        post_report_status = status
                        retry_delay = None
                    set_state(supervisor_status="REPORTING", notion_sync_status="NOTION_SYNC_BLOCKED")
                    notion_fallback(run_dir, task, review, commit_sha)
                    set_state(supervisor_status=post_report_status, notion_sync_status="NOTION_SYNC_BLOCKED")
                    completed += 1
                    if post_report_status == "RETRY_WAIT":
                        log(f"planner requested changes; retrying task={task['task_id']} after backoff={retry_delay}s", self.cfg)
                        self.wait(int(retry_delay))
                        if self.stop_requested or control() in {"PAUSE", "STOP"}:
                            continue
                        set_state(supervisor_status="READY_FOR_NEXT_CYCLE", retry_backoff_seconds=None, retry_resume_at=None)
                        continue
                    if review["verdict"] != "APPROVED":
                        exit_reason = post_report_status
                        break
                    set_state(supervisor_status="READY_FOR_NEXT_CYCLE")
                    if completed < self.max_cycles and time.monotonic() - started < self.max_runtime:
                        self.wait(self.cfg["cycle_wait_seconds"])
                except SupervisorInterrupted as interrupted:
                    final_status = "PAUSED" if interrupted.command == "PAUSE" else "COMPLETED"
                    latest = read_json(STATE_PATH)
                    state = set_state(
                        supervisor_status=final_status,
                        checkpoint_status=latest.get("supervisor_status"),
                        checkpoint_run_id=latest.get("last_run_id"),
                        next_run_allowed=interrupted.command == "PAUSE",
                    )
                    log(str(interrupted), self.cfg)
                    exit_reason = f"CONTROL_{interrupted.command}"
                    break
                except InfraFailure as error:
                    state = record_infra_failure(error)
                    log(f"infrastructure failure: {error}", self.cfg)
                    exit_reason = "INFRA_FAILURE"
                    break
                except Exception as error:
                    failures = read_json(STATE_PATH).get("consecutive_failures", 0) + 1
                    log(f"cycle failed safely: {type(error).__name__}: {error}", self.cfg)
                    latest = read_json(STATE_PATH)
                    failed_tasks = latest.get("failed_tasks", [])
                    if task["task_id"] not in failed_tasks:
                        failed_tasks.append(task["task_id"])
                    state = set_state(supervisor_status="FAILED_SAFE", consecutive_failures=failures, failed_tasks=failed_tasks, next_run_allowed=failures < self.cfg["max_consecutive_failures"])
                    if failure_limit_reached(failures, self.cfg):
                        exit_reason = "MAX_CONSECUTIVE_FAILURES"
                        break
                    self.wait(min(300, 2 ** failures * self.cfg["cycle_wait_seconds"]))
            if not self.stop_requested and completed >= self.max_cycles:
                exit_reason = "MAX_CYCLES"
            elif not self.stop_requested and time.monotonic() - started >= self.max_runtime:
                exit_reason = "MAX_RUNTIME"
            final = read_json(STATE_PATH)
            final["supervisor_pid"] = None
            final["supervisor_elapsed_seconds"] = int(time.monotonic() - started)
            final["supervisor_exit_reason"] = exit_reason
            final["supervisor_exited_at"] = now()
            atomic_json(STATE_PATH, final)
            return final


def run_nightly_release_first(cfg: dict[str, Any], max_cycles: int, max_runtime_seconds: int, manager: Any | None = None) -> dict[str, Any]:
    """Synchronize the authoritative admin approval before any development cycle."""
    if manager is None:
        try:
            from harness.release import ReleaseManager
        except ModuleNotFoundError:
            from release import ReleaseManager
        try:
            from harness.deploy_adapter import configured_release_manager
        except ModuleNotFoundError:
            from deploy_adapter import configured_release_manager
        manager = configured_release_manager(ROOT)
    release = manager.load()
    release_check = "NO_CANDIDATE"
    if release.get("release_id") not in {None, "none"}:
        synced = manager.sync_control_plane_approval()
        release = synced["contract"]
        release_check = synced["status"]
        if synced["status"] == "APPROVED":
            scheduled = release.get("scheduled_at")
            due = not scheduled or datetime.fromisoformat(scheduled.replace("Z", "+00:00")) <= datetime.now(timezone.utc)
            if due:
                reconcile = getattr(manager, "reconcile_production", None)
                reconciliation = reconcile() if callable(reconcile) else None
                if reconciliation and reconciliation.get("success"):
                    release_check = "RELEASED_AND_VERIFIED"
                    deployment = reconciliation
                elif reconciliation and reconciliation.get("result", {}).get("observed_sha") == release.get("approved_commit_sha"):
                    raise SupervisorError("Approved production SHA is already live but smoke verification is incomplete; duplicate deployment was blocked")
                else:
                    deployment = manager.deploy("production")
                if not deployment.get("success"):
                    raise SupervisorError("Production revision and smoke verification did not pass; development was not started")
                release_check = "RELEASED_AND_VERIFIED"
            else:
                release_check = "APPROVED_NOT_DUE"
    CONTROL_PATH.unlink(missing_ok=True)
    return {"release_check": release_check, "supervisor": Supervisor(cfg, max_cycles, max_runtime_seconds).run()}


def print_status() -> None:
    state = read_json(STATE_PATH)
    pid = read_json(PID_PATH).get("pid") if PID_PATH.exists() else None
    process_alive = bool(pid and pid_alive(int(pid)))
    state["process_alive"] = process_alive
    state["pid"] = pid if process_alive else None
    state["recorded_pid"] = pid
    state["pid_file_stale"] = bool(pid and not process_alive)
    print(json.dumps(state, ensure_ascii=False, indent=2))


def main() -> int:
    load_private_environment()
    load_private_environment(INFRA_ENV_PATH)
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    start = sub.add_parser("start"); start.add_argument("--background", action="store_true"); start.add_argument("--max-cycles", type=int); start.add_argument("--max-runtime-seconds", type=int)
    sub.add_parser("status"); sub.add_parser("pause"); sub.add_parser("resume"); sub.add_parser("stop"); sub.add_parser("logs")
    sub.add_parser("release-status"); sub.add_parser("release-plan"); sub.add_parser("release-dry-run"); sub.add_parser("release-staging"); sub.add_parser("release-production"); sub.add_parser("release-reconcile"); sub.add_parser("release-rollback"); sub.add_parser("release-logs")
    sub.add_parser("nightly-install"); sub.add_parser("nightly-status"); sub.add_parser("nightly-uninstall")
    release_approve = sub.add_parser("release-approve"); release_approve.add_argument("release_id"); release_approve.add_argument("commit_sha")
    nightly = sub.add_parser("nightly"); nightly.add_argument("--max-cycles", type=int, default=30); nightly.add_argument("--max-runtime-seconds", type=int, default=82800)
    dry = sub.add_parser("dry-run")
    args = parser.parse_args()
    cfg = config()
    try:
        if args.command == "nightly-install": print(json.dumps(install_nightly(cfg), ensure_ascii=False, indent=2)); return 0
        if args.command == "nightly-status": print(json.dumps(nightly_status(), ensure_ascii=False, indent=2)); return 0
        if args.command == "nightly-uninstall":
            if sys.platform == "darwin": subprocess.run(["launchctl", "bootout", f"gui/{os.getuid()}/{NIGHTLY_LABEL}"], cwd=ROOT, capture_output=True, text=True, check=False)
            NIGHTLY_PLIST_PATH.unlink(missing_ok=True); print(json.dumps({"installed": False, "label": NIGHTLY_LABEL})); return 0
        if args.command.startswith("release-"):
            try:
                from harness.release import ReleaseError, ReleaseManager
            except ModuleNotFoundError:
                from release import ReleaseError, ReleaseManager
            try:
                from harness.deploy_adapter import configured_release_manager
            except ModuleNotFoundError:
                from deploy_adapter import configured_release_manager
            manager = configured_release_manager(ROOT)
            if args.command == "release-status": print(json.dumps(manager.load(), ensure_ascii=False, indent=2)); return 0
            if args.command == "release-plan": print(json.dumps({"contract": manager.load(), "actual_deployment_enabled": os.getenv("AGENTOWN_RELEASE_ENVIRONMENT_CONFIGURED", "").lower() == "true", "reason": "Exact-SHA SSH adapter with revision, health, smoke, and application rollback is configured."}, ensure_ascii=False, indent=2)); return 0
            if args.command == "release-dry-run": print(json.dumps(manager.dry_run(), ensure_ascii=False, indent=2)); return 0
            if args.command == "release-logs": print((ROOT / "runs" / "release.log").read_text(encoding="utf-8")[-20000:] if (ROOT / "runs" / "release.log").exists() else "no release logs"); return 0
            if args.command == "release-approve": raise ReleaseError("production approval is accepted only from the admin@reviewdr.kr Releases page; CLI approval is disabled")
            if args.command == "release-staging": print(json.dumps(manager.deploy("staging"), ensure_ascii=False, indent=2)); return 0
            if args.command == "release-production": print(json.dumps(manager.deploy("production"), ensure_ascii=False, indent=2)); return 0
            if args.command == "release-reconcile": print(json.dumps(manager.reconcile_production(), ensure_ascii=False, indent=2)); return 0
            if args.command == "release-rollback": print(json.dumps(manager.rollback(), ensure_ascii=False, indent=2)); return 0
        if args.command == "nightly":
            result = run_nightly_release_first(cfg, args.max_cycles, args.max_runtime_seconds)
            print(json.dumps(result, ensure_ascii=False, indent=2)); return 0
        if args.command == "status": print_status(); return 0
        if args.command == "logs":
            print(LOG_PATH.read_text(encoding="utf-8")[-20000:] if LOG_PATH.exists() else "no logs")
            return 0
        if args.command in {"pause", "stop"}:
            atomic_json(CONTROL_PATH, {"command": args.command.upper(), "requested_at": now()}); print_status(); return 0
        if args.command == "resume":
            atomic_json(CONTROL_PATH, {"command": "RUN", "requested_at": now()})
            set_state(next_run_allowed=True, supervisor_status="READY_FOR_NEXT_CYCLE", consecutive_failures=0)
            print_status()
            return 0
        if args.command == "dry-run":
            print(json.dumps(Supervisor(cfg).run(dry_run=True), ensure_ascii=False, indent=2)); return 0
        CONTROL_PATH.unlink(missing_ok=True)
        if args.background:
            pid, log_path = start_background(args)
            print(json.dumps({"started": True, "pid": pid, "log": log_path})); return 0
        result = Supervisor(cfg, args.max_cycles, args.max_runtime_seconds).run()
        print(json.dumps(result, ensure_ascii=False, indent=2)); return 0
    except (SupervisorError, RuntimeError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
