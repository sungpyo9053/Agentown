from __future__ import annotations

import asyncio
import os
import shutil
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
        if not codex_home or not os.path.isfile(os.path.join(codex_home, "auth.json")):
            raise ExecutionNotConfigured("Codex CLI authentication is unavailable")
        prompt = "\n\n".join(
            f"<{message.role}>\n{message.content or ''}\n</{message.role}>" for message in messages
        )
        process = await asyncio.create_subprocess_exec(
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
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env={
                "PATH": "/usr/local/bin:/usr/bin:/bin",
                "CODEX_HOME": codex_home,
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
        if process.returncode != 0:
            safe = stderr.decode("utf-8", errors="replace")[-2000:]
            raise RuntimeError(f"Codex CLI execution failed: {safe}")
        output = stdout.decode("utf-8", errors="replace").strip()
        if not output:
            raise RuntimeError("Codex CLI returned an empty result")
        return Message(role="assistant", content=output)
