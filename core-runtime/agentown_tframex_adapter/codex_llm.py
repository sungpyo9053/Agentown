from __future__ import annotations

import asyncio
import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any, AsyncGenerator, List, Union

from tframex.models.primitives import Message, MessageChunk
from tframex.util.llms import BaseLLMWrapper

from .adapter import ExecutionNotConfigured


class CodexCliLLMWrapper(BaseLLMWrapper):
    """TFrameX LLM transport backed by the server's authenticated Codex CLI."""

    def __init__(self, command: str = "codex", model: str = "gpt-5.6-luna", timeout_seconds: int = 120):
        super().__init__(model_id=model)
        self.command = command
        self.timeout_seconds = timeout_seconds

    async def chat_completion(
        self, messages: List[Message], stream: bool = False, **kwargs: Any
    ) -> Union[Message, AsyncGenerator[MessageChunk, None]]:
        if stream:
            raise ExecutionNotConfigured("Codex CLI streaming is not configured")
        executable = shutil.which(self.command)
        if executable is None:
            raise ExecutionNotConfigured(f"Codex CLI '{self.command}' is unavailable")
        codex_home = os.environ.get("CODEX_HOME")
        auth_file = os.path.join(codex_home, "auth.json") if codex_home else ""
        if not auth_file or not os.path.isfile(auth_file) or not os.access(auth_file, os.R_OK):
            raise ExecutionNotConfigured("Codex CLI authentication is unavailable")
        prompt = "\n\n".join(
            f"<{message.role}>\n{message.content or ''}\n</{message.role}>" for message in messages
        )
        output_schema = kwargs.pop("output_schema", None)
        schema_path = None
        command_args = [
            executable,
            "exec",
            "-",
            "--ephemeral",
            "--ignore-user-config",
            "--ignore-rules",
            "--strict-config",
            "--sandbox",
            "read-only",
            "--skip-git-repo-check",
            "--disable",
            "shell_tool",
            "-c",
            "tools.web_search=false",
            "-c",
            "agents.enabled=false",
            "-c",
            "shell_environment_policy.inherit=none",
            "-c",
            "history.persistence=none",
            "--model",
            self.model_id,
            "--color",
            "never",
        ]
        if output_schema:
            schema_file = tempfile.NamedTemporaryFile(mode="w", suffix=".json", encoding="utf-8", delete=False)
            try:
                json.dump(_json_schema(output_schema), schema_file, ensure_ascii=False)
                schema_path = schema_file.name
            finally:
                schema_file.close()
            command_args.extend(["--output-schema", schema_path])
        # Production mounts the shared credential directory read-only. Codex still
        # needs a private writable home for its app-server and PATH alias state, so
        # expose only an ephemeral copy of auth.json to each invocation.
        with tempfile.TemporaryDirectory(prefix="agentown-codex-") as writable_home:
            private_auth = Path(writable_home, "auth.json")
            shutil.copyfile(auth_file, private_auth)
            private_auth.chmod(0o600)
            process = await asyncio.create_subprocess_exec(
                *command_args,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env={
                    "PATH": "/usr/local/bin:/usr/bin:/bin",
                    "HOME": writable_home,
                    "CODEX_HOME": writable_home,
                    "LANG": "C.UTF-8",
                },
            )
            try:
                stdout, stderr = await asyncio.wait_for(
                    process.communicate(prompt.encode("utf-8")), timeout=self.timeout_seconds
                )
            except asyncio.TimeoutError as exc:
                process.kill()
                await process.wait()
                raise RuntimeError("Codex CLI execution timed out") from exc
            finally:
                if schema_path:
                    try:
                        os.unlink(schema_path)
                    except FileNotFoundError:
                        pass
        if process.returncode != 0:
            safe = stderr.decode("utf-8", errors="replace")[-2000:]
            raise RuntimeError(f"Codex CLI execution failed: {safe}")
        output = stdout.decode("utf-8", errors="replace").strip()
        if not output:
            raise RuntimeError("Codex CLI returned an empty result")
        return Message(role="assistant", content=output)


def _json_schema(fields: list[dict[str, Any]]) -> dict[str, Any]:
    properties = {
        str(field["name"]): _field_schema(field, nullable=not bool(field.get("required")))
        for field in fields
    }
    # Codex/OpenAI strict structured outputs require every property to appear in
    # `required`. Domain-optional fields remain optional by accepting JSON null.
    required = [str(field["name"]) for field in fields]
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "additionalProperties": False,
        "properties": properties,
        "required": required,
    }


def _field_schema(field: dict[str, Any], *, nullable: bool = False) -> dict[str, Any]:
    field_type = str(field.get("type") or "string").lower()
    schema: dict[str, Any] = {"type": field_type}
    description = str(field.get("description") or "")
    format_hint = {
        "uri": "반드시 http:// 또는 https://로 시작하는 절대 URL이어야 합니다.",
        "date": "반드시 YYYY-MM-DD 날짜여야 합니다.",
        "date-time": "반드시 ISO 8601 날짜와 시간이어야 합니다.",
    }.get(field.get("format"))
    if description or format_hint:
        schema["description"] = " ".join(part for part in (description, format_hint) if part)
    if field_type == "array":
        item_schema = field.get("itemSchema") or []
        if item_schema:
            schema["items"] = _json_schema(item_schema)
        else:
            schema["items"] = {"type": str(field.get("itemType") or "string").lower()}
            item_format_hint = {
                "uri": "각 항목은 반드시 http:// 또는 https://로 시작하는 절대 URL이어야 합니다.",
                "date": "각 항목은 반드시 YYYY-MM-DD 날짜여야 합니다.",
                "date-time": "각 항목은 반드시 ISO 8601 날짜와 시간이어야 합니다.",
            }.get(field.get("itemFormat"))
            if item_format_hint:
                schema["items"]["description"] = item_format_hint
            if field.get("itemMinLength") is not None:
                schema["items"]["minLength"] = int(field["itemMinLength"])
        # Codex structured outputs currently rejects the JSON Schema `uniqueItems`
        # keyword. Runtime contract validation still enforces it after generation.
        for source, target in (("minItems", "minItems"), ("maxItems", "maxItems")):
            if field.get(source) is not None:
                schema[target] = field[source]
    elif field_type == "object":
        nested = field.get("objectSchema") or []
        schema = _json_schema(nested)
        if field.get("description"):
            schema["description"] = str(field["description"])
    else:
        # Response-format schemas reject URI and date formats. The adapter's
        # post-generation validator enforces those runtime constraints.
        for source, target in (("enumValues", "enum"), ("minimum", "minimum"), ("maximum", "maximum"), ("minLength", "minLength")):
            if field.get(source) is not None:
                schema[target] = field[source]
    if nullable:
        schema["type"] = [schema["type"], "null"]
    return schema
