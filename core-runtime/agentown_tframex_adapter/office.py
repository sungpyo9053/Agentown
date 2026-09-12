"""Opt-in loopback-only viewer for a downloaded package. No hosted service calls."""
from __future__ import annotations

import copy
from hashlib import sha256
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from jsonschema import Draft202012Validator
from .file_input import read_office_file, MAX_FILE_BYTES


class OfficeTrace(list):
    def __init__(self, office):
        super().__init__()
        self.office = office

    def append(self, event):
        super().append(event)
        self.office.record(event)


class LocalOffice:
    def __init__(self, root: Path, *, input_schema=None):
        self.results_root = (root / "results").resolve()
        self.artifact = None
        self.input_schema = copy.deepcopy(input_schema)
        self.input_ready = threading.Event()
        self.submitted_input = None
        if input_schema is not None:
            Draft202012Validator.check_schema(input_schema)
        bundle = json.loads((root / "design-bundle.json").read_text(encoding="utf-8"))
        workflow = json.loads((root / "workflow.json").read_text(encoding="utf-8"))
        self.lock = threading.Lock()
        self.import_lock = threading.Lock()
        self.nodes = {}
        self.step_labels = {}
        self.running_steps = {}
        for node in workflow["nodes"]:
            key = (node.get("config") or {}).get("agentKey")
            runtime_key = key or node.get("nodeType", "tool")
            self.step_labels[f"{runtime_key.replace('.', '-')}__{node['id']}"] = node.get("label") or "실행 단계"
            if key:
                self.nodes[f"{key.replace('.', '-')}__{node['id']}"] = key
        self.state = {"name": bundle["proposal"]["name"], "status": "IDLE", "employees": [
            {"key": a["key"], "name": a["name"], "role": a["role"], "status": "IDLE", "output": ""}
            for a in bundle["agentDefinitions"]
        ]}
        self.active = set()
        self.failed = set()
        self.errors = {}
        self.output_fields = {a["key"]: [f["name"] for f in a.get("outputSchema", [])]
                              for a in bundle["agentDefinitions"]}
        self.html = (root / "company/index.html").read_bytes()
        self.token = secrets.token_urlsafe(32)
        office = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass  # Do not log the capability URL or user results.

            def do_POST(self):
                origin = f"http://127.0.0.1:{office.server.server_port}"
                if self.headers.get("Host") != origin.removeprefix("http://") or self.headers.get("Origin") != origin:
                    self.send_error(403)
                    return
                if self.path == f"/{office.token}/attachment" and office.input_schema is not None:
                    self.read_attachment()
                    return
                if self.path != f"/{office.token}/input" or office.input_schema is None:
                    self.send_error(404)
                    return
                if self.headers.get("Content-Type") != "application/json" or self.headers.get("Transfer-Encoding"):
                    self.send_error(415)
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 1024 * 1024:
                        self.send_error(413)
                        return
                    self.connection.settimeout(5)
                    value = json.loads(self.rfile.read(length))
                    if not isinstance(value, dict) or not Draft202012Validator(office.input_schema).is_valid(value):
                        self.send_error(422, "Input does not match package schema")
                        return
                except (ValueError, OSError):
                    self.send_error(400)
                    return
                with office.lock:
                    if office.submitted_input is not None:
                        self.send_error(409)
                        return
                    office.submitted_input = value
                    office.state["status"] = "RUNNING"
                    office.input_ready.set()
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", "0")
                self.end_headers()

            def read_attachment(self):
                if office.input_ready.is_set():
                    self.send_error(409)
                    return
                if self.headers.get('Content-Type') != 'application/octet-stream' or self.headers.get('Transfer-Encoding'):
                    self.send_error(415)
                    return
                if not office.import_lock.acquire(blocking=False):
                    self.send_error(429)
                    return
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if not 0 < length <= MAX_FILE_BYTES:
                        self.send_error(413)
                        return
                    self.connection.settimeout(10)
                    value = read_office_file(self.rfile.read(length), self.headers.get('X-Agentown-Format', ''))
                    payload = json.dumps(value, ensure_ascii=False).encode('utf-8')
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json; charset=utf-8')
                    self.send_header('Cache-Control', 'no-store')
                    self.send_header('X-Content-Type-Options', 'nosniff')
                    self.send_header('Content-Length', str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except (ValueError, OSError):
                    self.send_error(422, 'Cannot safely read document; original input is preserved')
                finally:
                    office.import_lock.release()

            def do_GET(self):
                if self.headers.get("Host") != f"127.0.0.1:{office.server.server_port}":
                    self.send_error(403)
                    return
                if self.path == f"/{office.token}/":
                    payload, content_type = office.html, "text/html; charset=utf-8"
                elif self.path == f"/{office.token}/state":
                    payload = json.dumps(office.snapshot(), ensure_ascii=False).encode("utf-8")
                    content_type = "application/json; charset=utf-8"
                elif self.path == f"/{office.token}/artifact" and office.artifact is not None:
                    payload, filename = office.artifact
                    content_type = "application/octet-stream"
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                if self.path == f"/{office.token}/artifact":
                    self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}/{self.token}/"

    def start(self):
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def snapshot(self):
        with self.lock:
            snapshot = copy.deepcopy(self.state)
            if self.input_schema is not None and self.submitted_input is None:
                snapshot["inputSchema"] = copy.deepcopy(self.input_schema)
            snapshot["activeSteps"] = [{"label": self.step_labels.get(node, "실행 단계"),
                "elapsedSeconds": max(0, int(time.monotonic() - started))}
                for node, started in self.running_steps.items()]
            snapshot["error"] = self.state.get("error") or "\n".join(self.errors.values()) or self.state.get("artifactError", "")
            return snapshot

    def record(self, event):
        kind, node = event.get("kind"), event.get("agent")
        if kind not in {"agent_start", "agent_end", "agent_error"}:
            return
        with self.lock:
            key = self.nodes.get(node)
            if kind == "agent_start":
                self.running_steps[node] = time.monotonic()
                self.active.add(node)
                self.failed.discard(node)
                self.errors.pop(node, None)
            else:
                self.running_steps.pop(node, None)
                self.active.discard(node)
                if kind == "agent_error":
                    self.failed.add(node)
                    self.errors[node] = str(event.get("error", "실행 실패"))
            for employee in self.state["employees"]:
                if employee["key"] != key:
                    continue
                if kind == "agent_start":
                    employee.update(status="RUNNING", output="")
                else:
                    failed = kind == "agent_error"
                    output = str(event.get("error" if failed else "output", ""))
                    if not failed and self.output_fields.get(key):
                        try:
                            value = json.loads(output)
                            if isinstance(value, dict):
                                output = json.dumps({field: value[field] for field in self.output_fields[key]
                                                     if field in value}, ensure_ascii=False)
                        except (ValueError, TypeError):
                            pass
                    employee.update(status="FAILED" if failed else "SUCCEEDED", output=output)
                errors = [self.errors[n] for n in sorted(self.failed) if self.nodes.get(n) == key]
                if errors:
                    employee.update(status="FAILED", output="\n".join(errors))
                elif any(self.nodes.get(n) == key for n in self.active):
                    employee["status"] = "RUNNING"

    def finish(self, status, error=None, output=None):
        with self.lock:
            self.state["status"] = status
            self.artifact = None
            self.state.pop("artifact", None)
            self.state.pop("artifactError", None)
            if status == "SUCCEEDED" and isinstance(output, dict) and output.get("artifactPath"):
                try:
                    path = Path(output["artifactPath"]).resolve(strict=True)
                    if not path.is_relative_to(self.results_root) or path.suffix not in {".zip", ".docx", ".pptx", ".xlsx"}:
                        raise ValueError("Unexpected artifact location")
                    with path.open("rb") as file:
                        content = file.read(64 * 1024 * 1024 + 1)
                    if len(content) > 64 * 1024 * 1024 or len(content) != output.get("artifactBytes") or sha256(content).hexdigest() != output.get("artifactSha256"):
                        raise ValueError("Artifact integrity mismatch")
                    filename = "agentown-result" + path.suffix
                    # Serve only this verified immutable result, never an arbitrary
                    # path or a file selected through a browser query parameter.
                    self.artifact = (content, filename)
                    self.state["artifact"] = {"name": filename, "bytes": len(content)}
                except (OSError, ValueError, TypeError):
                    self.state["artifactError"] = "결과 파일을 안전하게 확인하지 못했습니다. 실행기의 results 폴더를 확인해 주세요."
            if error is not None:
                self.state["error"] = str(error)[:2000]
            if status != "RUNNING":
                self.running_steps.clear()
            for employee in self.state["employees"]:
                if employee["status"] == "RUNNING" and status != "RUNNING":
                    employee["status"] = "INTERRUPTED"
