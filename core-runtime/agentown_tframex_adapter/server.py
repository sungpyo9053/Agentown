from __future__ import annotations

import json
import os
from typing import Any, Dict

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .adapter import AgentownTFrameXAdapter, DefinitionError, ExecutionNotConfigured
from .codex_llm import CodexCliLLMWrapper
from .capabilities import BUILTIN_TOOLS


class ExecutionRequest(BaseModel):
    definition: Dict[str, Any]


app = FastAPI(title="Agentown pinned TFrameX runtime", docs_url=None, redoc_url=None)


@app.get("/health")
def health():
    return {
        "status": "UP",
        "runtime": "TFrameX",
        "tframexCommit": "23d7a45dd9e2e52f54f44ff8f63c6dff28ef8603",
    }


@app.post("/execute")
async def execute(request: ExecutionRequest):
    try:
        llm = CodexCliLLMWrapper(
            command=os.environ.get("AGENTOWN_CODEX_COMMAND", "codex"),
            model=os.environ.get("AGENTOWN_CODEX_MODEL", "gpt-5.6-luna"),
            timeout_seconds=int(os.environ.get("AGENTOWN_CODEX_TIMEOUT_SECONDS", "120")),
        )
        result = await AgentownTFrameXAdapter(llm=llm, tools=BUILTIN_TOOLS).run(request.definition)
        final = result.get("final") or ""
        unconfigured = next((
            item.get("error") for item in result.get("trace", [])
            if item.get("kind") in {"agent_error", "tool_error"}
            and any(marker in str(item.get("error", "")).lower() for marker in ("unavailable", "not configured", "unconfigured"))
        ), None)
        if unconfigured:
            return JSONResponse(status_code=409, content={"status": "EXECUTION_NOT_CONFIGURED", "code": "EXECUTION_NOT_CONFIGURED", "message": str(unconfigured), **result})
        error = _runtime_error(final, result.get("sharedData") or {})
        if error:
            return JSONResponse(status_code=422, content={"status": "FAILED", "code": "TFRAMEX_EXECUTION_FAILED", "message": error, **result})
        try:
            output = json.loads(final)
        except json.JSONDecodeError:
            output = {"result": final}
        return {"status": "SUCCEEDED", "output": output, **result}
    except ExecutionNotConfigured as exc:
        return JSONResponse(status_code=409, content={"status": "EXECUTION_NOT_CONFIGURED", "code": exc.code, "message": str(exc)})
    except DefinitionError as exc:
        return JSONResponse(status_code=400, content={"status": "FAILED", "code": "INVALID_TFRAMEX_DEFINITION", "message": str(exc)})
    except Exception as exc:
        return JSONResponse(status_code=422, content={"status": "FAILED", "code": "TFRAMEX_EXECUTION_FAILED", "message": str(exc)})


def _runtime_error(final: str, shared_data: Dict[str, Any]) -> str | None:
    lowered = final.lower()
    if "error in flow" in lowered or "error executing agent" in lowered or "task '" in lowered and " failed:" in lowered:
        return final[:2000]
    for value in shared_data.values():
        if isinstance(value, list) and any(isinstance(item, dict) and item.get("status") == "error" for item in value):
            return "A TFrameX pattern child failed"
    return None


def main():
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8090)
