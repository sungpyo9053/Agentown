# CSV 변경 행 비교

두 CSV를 일반 코드로 정확히 비교하고 변경된 행을 구조화합니다.

## What Agentown verified

- Structured Agent and Workflow contracts
- Server catalog resource references
- Graph connectivity and approval gates
- Safe Mock simulation contract

## Execution boundary

Configured resources can be connected by a compatible runtime.

Import `agent.yaml` and `workflow.yaml` into a compatible runner. Provide secrets through the runner, never by editing prompts or workflow files.

## Local TFrameX test

`python3 -m venv .venv && .venv/bin/pip install ./runtime` installs the pinned TFrameX runtime.
`.venv/bin/python runners/python/runner.py` executes this package through the same Agentown TFrameX Adapter used by the service.
If an Agent, Tool, connector, or Codex authentication is unavailable, execution returns `EXECUTION_NOT_CONFIGURED` and never substitutes Mock output.
