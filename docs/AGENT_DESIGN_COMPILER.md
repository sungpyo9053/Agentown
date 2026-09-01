# Agentown Agent Design Compiler

## Product definition

하고 싶은 일을 자연어로 설명하면, Agentown이 필요한 에이전트와 도구·워크플로를 설계하고 직접 시험해볼 수 있는 실행 패키지로 만들어줍니다.

Agentown은 장기 실행 인프라가 아니라 에이전트 컴파일러와 패키저다.

```text
Natural language
  -> Requirement Spec
  -> Agent Graph
  -> Validated Agent Package
  -> Mock Sandbox or external Runtime
```

## Existing structure and retained contracts

- Kotlin/Spring Builder already persists requirements, proposals and immutable Workflow Versions in PostgreSQL JSONB.
- `StructuredMetaAgentPipeline` parses structured output, normalizes it and rejects invalid designs.
- `WorkflowGraphValidator` verifies the server node catalog, connectivity, cycles, approval before side effects and requirement fidelity.
- Next.js Builder renders the server Workflow Version with ReactFlow; it does not keep a separate executable graph.
- Mock Slack, Notion and News node contracts already provide safe step-run persistence and approval pause/resume.
- `HarnessPackageRenderer` already exported the graph and derived Agent/Guide Markdown.

The compiler extension adds a runtime-neutral Agent Design, trusted resource resolution, an independent design review, the `agentown-agent-package/v1` contract and a fixed Python Mock adapter. No database migration is needed: draft design fields remain in the existing proposal JSONB and immutable approved graphs remain in `builder_workflow_versions`.

## Compiler stages

1. LLM transport DTO: parse the schema-constrained response without treating it as a trusted domain object.
2. Requirement Analyzer: preserve the goal, trigger, inputs, outputs, quality constraints and missing questions.
3. Agent Architect: keep judgment work in Agent definitions and classify deterministic work as Function, Template or Tool.
4. Capability Resolver: bind only resources present in the server catalog and distinguish installed, connectable and missing resources.
5. Workflow Composer: produce the runtime graph and the runtime-neutral START/TRIGGER/AGENT/FUNCTION/TOOL/TEMPLATE/APPROVAL/OUTPUT/END view.
6. Design Reviewer: reject missing resources or unreferenced Agents and report AI-call cost risk.
7. Server Validator: validate the concrete graph before approval/version creation.
8. Packager: export a complete package only after a validated Workflow Version exists.

## Agent Package v1

```text
agent.yaml
workflow.yaml
prompts/system.md
prompts/reviewer.md
schemas/input.schema.json
schemas/output.schema.json
skills/README.md
tools/tools.yaml
mcp.json
examples/sample-input.json
.env.example
runners/python/runner.py
runtime-targets.json
README.md
```

Compatibility files (`workflow.json`, `design-bundle.json`, `agents/*.md`, `guides/*.md`, `CODEX.md`, `AGENTS.md`) remain available.

## Responsibility boundary

Agentown verifies schemas, server-catalog references, graph connectivity, approval gates, required environment variable names, Mock behavior and package completeness. It does not guarantee external API availability, user credentials, third-party permissions, external runtime availability, every generated answer, long-running operations or an SLA.

## Reference projects and licenses

No source code was copied. Concepts were reimplemented in the existing Kotlin/Next.js style.

- Nexent (MIT): natural-language Agent drafting, minimal resource selection, Tool/Skill/Memory/MCP catalog and Agent versions.
- Tesslate Agent-Builder (MIT): Orchestrator intent followed by Flow Builder graph generation and ReactFlow rendering.
- Sim (Apache-2.0): chat/canvas workspace, pre-run testing and visible run activity.
- Eko (MIT): dependency-aware workflow concepts and explicit pause/resume/interrupt snapshots. Runtime recovery is outside this MVP.
- builtbyV Agent Builder (MIT): portable Skill/MCP harness files and progressive resource disclosure.

## Deferred

Real Slack/Notion calls, OAuth verification, high-availability scheduling, Kubernetes isolation, runtime SLA, production pause/resume recovery and broad export adapters are not part of this compiler MVP.
