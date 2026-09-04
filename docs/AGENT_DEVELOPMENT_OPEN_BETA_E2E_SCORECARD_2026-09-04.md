# Agent Development Open Beta E2E Scorecard

Date: 2026-09-05  
Target: authenticated users on `https://reviewdr.kr/develop`  
Release verdict: **FAIL — remediation cycle in progress**

## Acceptance rule

An item passes only when a fresh production UI request creates a synchronized immutable version, the downloaded package runs through the pinned TFrameX runtime, and its trace, failure status, and final output satisfy the generated graph and schemas. Local, staging, deployment, Control Plane, and production E2E evidence are recorded separately. A rendered canvas or `SUCCEEDED` label alone is not evidence.

## Functional matrix

| ID | Capability from product/runtime contract | Required production evidence | Current result | Evidence or defect |
|---|---|---|---|---|
| F-01 | `/develop`, login, tenant-owned project/session access | Authenticated route and existing versions load without cross-tenant data | PASS | Production route, session, and Version 1/2 retrieval previously verified; tenant-security rerun remains in security section. |
| F-02 | Korean natural-language design and immutable version/canvas synchronization | Fresh request ends with server latest `versionId` equal to canvas `versionId` | PASS | First blind branch-review request created Version 1 and synchronized canvas. |
| F-03 | Failed/timed-out generation preserves input and explicit failed state | Forced failure retains original text and does not create an empty swallowed session | PENDING | Must be rerun against the remediation SHA. |
| F-04 | Deterministic workflow with no Agent | CSV compare package executes exact add/modify/remove behavior | PENDING | Local package contract passes; fresh production UI/download/run required. |
| F-05 | Single Agent | One bounded Agent receives declared external input and returns schema-valid output | PENDING | Fresh blind production case required. |
| F-06 | Sequential multi-Agent | Downstream Agent starts after upstream completion and receives declared output | PENDING | Differential path exists; fresh blind production case required. |
| F-07 | Parallel Agent tasks and explicit Join | Three independent tasks overlap; reporter starts only after all three end | PENDING | Final local blind warehouse package runs three real Agent task instances through TFrameX and starts Reporter only after all three end; fresh production proof is still required. |
| F-08 | Router/condition branch | Real TFrameX `RouterPattern` selects exactly one route from runtime data | PENDING | Local differential suite covers deterministic Router selection; fresh production package proof is still required. |
| F-09 | Partial child failure | Missing/failed child prevents full success and yields `PARTIAL` or `FAILED`; reporter cannot hide it | PENDING | Local runtime now blocks reporter after child failure; production package rerun required. |
| F-10 | Unsupported connector safety | Missing connector returns `EXECUTION_NOT_CONFIGURED`, with no mock success or external call | PENDING | Fresh blind production case required. |
| F-11 | Agent/Tool input-output and final schema validation | Invalid required field/type or invalid final result cannot be `SUCCEEDED` | PENDING | Local contract rejects missing, mistyped, undeclared and internal inputs/outputs; immutable final schema is shared by service and package. Fresh production proof is required. |
| F-12 | UI execution and downloaded package runtime parity | Same fixture produces structurally identical agents, order, tool calls, route, failure and result | PENDING | Pinned TFrameX differential suite has 26 passing cases and the blind warehouse fixture passes through the downloaded-package Runner; production pair required. |
| F-13 | Flow import/export and immutable new version | Export/import round-trip preserves graph meaning and creates a new version; stale hash rejected | PENDING | Repository integration tests exist; production E2E required. |
| F-14 | Package completeness | ZIP contains pinned runtime resources and starts after extraction | PASS | Production ZIP contained `pyproject.toml`, adapter, server and runner after SHA `a55a516420f43f331d4636688e40bffad5e98fb6`. |
| F-15 | Real Agent execution configuration | Missing Codex executable/auth is `EXECUTION_NOT_CONFIGURED`; configured run invokes actual Agent | PENDING | Local blind package invokes real TFrameX `LLMAgent.run`; no fixed sample fallback remains. Production configured execution is required. |
| F-16 | External-write safety | No undeclared network write; declared write requires capability and human approval | PENDING | Blind read-only case made no external call; write-capability safety case required. |
| F-17 | Observability and administrator metrics | Anonymized events retain natural-language request, generation/run/version/package/capability history | PENDING | Admin implementation exists; production event-to-dashboard reconciliation required. |
| F-18 | Recovery, retry, concurrency | Queued/running recovery, retry policy, per-user limits and idempotency survive restart/failure | PENDING | Local fault injection now proves one retry for transient Codex timeout/empty/CLI/start failure, retry exhaustion with one durable failure, and no retry after cancellation. Production restart and concurrency evidence remains required. |

## Security and release gates

| ID | Gate | Current result | Required evidence |
|---|---|---|---|
| S-01 | Tenant isolation for sessions, versions, runs, packages and results | PENDING | Two-user negative authorization E2E and audit evidence. |
| S-02 | Secrets absent from package, logs, prompts and stored activity | PENDING | Automated secret scan plus representative production audit. |
| S-03 | Exact-SHA staging, health, smoke, database compatibility and rollback | PASS for `a55a516…` only | Every remediation SHA must independently pass; an older SHA does not transfer. |
| S-04 | Control Plane decision and observed production SHA agree | PENDING for next remediation | Release record, preflight hash, deployed revision and public revision must match. |
| S-05 | Public/open-beta readiness | FAIL | All F/S mandatory rows must be PASS before opening. |

## Current remediation cycle

1. Translate `condition.branch` to the pinned TFrameX `RouterPattern` and keep the real upstream execution path.
2. Execute `quality.check` and plain-text rendering as registered deterministic Tools.
3. Preserve `EXECUTION_NOT_CONFIGURED` across TFrameX's caught-error behavior.
4. Remove invented runtime human approval when the request only says an Agent reviews data.
5. Separate external package inputs from internal Agent-to-Agent fields; support per-task `inputDefaults`.
6. Validate every Agent contract and the final output contract before success.
7. Run source differential, backend, package, container and staging gates; deploy the exact SHA.
8. Run fresh blind production cases and update this scorecard. Repeat until every mandatory row passes.
9. Retry one transient Codex generation failure with the same durable job and idempotency key; preserve one explicit failed result if the retry is exhausted and never restart a cancelled job.

## Latest local evidence

- Backend regression: 245 tests discovered, 239 executed, 0 failures, 0 errors, 6 explicitly skipped environment-gated tests.
- Frontend: TypeScript check and the Node 20 production build passed.
- Pinned TFrameX differential: 26 tests passed against the fixed upstream runtime SHA, including explicit array fan-in and invalid binding rejection.
- Fresh blind domain generation: a warehouse inventory request produced three parallel Agent task instances and an explicit fan-in Reporter without `parallel.map.mock`.
- Downloaded package execution: the rendered package's own `runners/python/runner.py` ran the fixture, emitted 8 Agent trace events, preserved all three warehouse names and evidence IDs, and returned a schema-valid joined result. The final complete fresh Golden finished in 5m28s.
- External workflow input: only `warehouses`, `asOfDate`, and `fixture` are exposed; internal Agent fields are rejected before a run is created.
- These are local candidate facts only. The overall verdict remains FAIL until exact-SHA staging, Control Plane approval, production deployment, and the full fresh production scorecard pass.
