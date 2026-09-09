"""Opt-in loopback-only viewer for a downloaded package. No hosted service calls."""
from __future__ import annotations

import copy
import json
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class OfficeTrace(list):
    def __init__(self, office):
        super().__init__()
        self.office = office

    def append(self, event):
        super().append(event)
        self.office.record(event)


class LocalOffice:
    def __init__(self, root: Path):
        bundle = json.loads((root / "design-bundle.json").read_text(encoding="utf-8"))
        workflow = json.loads((root / "workflow.json").read_text(encoding="utf-8"))
        self.lock = threading.Lock()
        self.nodes = {}
        for node in workflow["nodes"]:
            key = (node.get("config") or {}).get("agentKey")
            if key:
                self.nodes[f"{key.replace('.', '-')}__{node['id']}"] = key
        self.state = {"name": bundle["proposal"]["name"], "status": "IDLE", "employees": [
            {"key": a["key"], "name": a["name"], "role": a["role"], "status": "IDLE", "output": ""}
            for a in bundle["agentDefinitions"]
        ]}
        self.active = set()
        self.failed = set()
        self.html = (root / "company/index.html").read_bytes()
        self.token = secrets.token_urlsafe(32)
        office = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass  # Do not log the capability URL or user results.

            def do_GET(self):
                if self.headers.get("Host") != f"127.0.0.1:{office.server.server_port}":
                    self.send_error(403)
                    return
                if self.path == f"/{office.token}/":
                    payload, content_type = office.html, "text/html; charset=utf-8"
                elif self.path == f"/{office.token}/state":
                    payload = json.dumps(office.snapshot(), ensure_ascii=False).encode("utf-8")
                    content_type = "application/json; charset=utf-8"
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", content_type)
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
            return copy.deepcopy(self.state)

    def record(self, event):
        kind, node = event.get("kind"), event.get("agent")
        if kind not in {"agent_start", "agent_end", "agent_error"}:
            return
        with self.lock:
            key = self.nodes.get(node)
            for employee in self.state["employees"]:
                if employee["key"] != key:
                    continue
                if kind == "agent_start":
                    self.active.add(node)
                    self.failed.discard(node)
                    employee.update(status="RUNNING", output="")
                else:
                    self.active.discard(node)
                    failed = kind == "agent_error"
                    if failed:
                        self.failed.add(node)
                    employee.update(status="FAILED" if failed else "SUCCEEDED", output=str(event.get("error" if failed else "output", "")))
                    if any(self.nodes.get(n) == key for n in self.failed):
                        employee["status"] = "FAILED"
                    elif any(self.nodes.get(n) == key for n in self.active):
                        employee["status"] = "RUNNING"

    def finish(self, status):
        with self.lock:
            self.state["status"] = status
            for employee in self.state["employees"]:
                if employee["status"] == "RUNNING" and status != "RUNNING":
                    employee["status"] = "INTERRUPTED"
