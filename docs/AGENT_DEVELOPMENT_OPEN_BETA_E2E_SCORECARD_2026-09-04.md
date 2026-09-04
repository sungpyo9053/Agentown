# Agent Development Open Beta E2E Scorecard

Date: 2026-09-04  
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
| F-07 | Parallel Agent tasks and explicit Join | Three independent tasks overlap; reporter starts only after all three end | FAIL | Production blind design had three workers and fan-in, but downloaded package returned `EXECUTION_NOT_CONFIGURED` because `condition.branch` was not translated. |
| F-08 | Router/condition branch | Real TFrameX `RouterPattern` selects exactly one route from runtime data | FAIL | First production package could not compile `condition.branch`; remediation adds deterministic router selection through upstream `RouterPattern`. |
| F-09 | Partial child failure | Missing/failed child prevents full success and yields `PARTIAL` or `FAILED`; reporter cannot hide it | PENDING | Local runtime now blocks reporter after child failure; production package rerun required. |
| F-10 | Unsupported connector safety | Missing connector returns `EXECUTION_NOT_CONFIGURED`, with no mock success or external call | PENDING | Fresh blind production case required. |
| F-11 | Agent/Tool input-output and final schema validation | Invalid required field/type or invalid final result cannot be `SUCCEEDED` | FAIL | First blind package exposed internal fields (`location`, `reviewResults`) as user input and never reached final validation. |
| F-12 | UI execution and downloaded package runtime parity | Same fixture produces structurally identical agents, order, tool calls, route, failure and result | PENDING | Differential suite passes locally; production pair required. |
| F-13 | Flow import/export and immutable new version | Export/import round-trip preserves graph meaning and creates a new version; stale hash rejected | PENDING | Repository integration tests exist; production E2E required. |
| F-14 | Package completeness | ZIP contains pinned runtime resources and starts after extraction | PASS | Production ZIP contained `pyproject.toml`, adapter, server and runner after SHA `a55a516420f43f331d4636688e40bffad5e98fb6`. |
| F-15 | Real Agent execution configuration | Missing Codex executable/auth is `EXECUTION_NOT_CONFIGURED`; configured run invokes actual Agent | PENDING | No fixed sample fallback remains locally; production configured execution required. |
| F-16 | External-write safety | No undeclared network write; declared write requires capability and human approval | PENDING | Blind read-only case made no external call; write-capability safety case required. |
| F-17 | Observability and administrator metrics | Anonymized events retain natural-language request, generation/run/version/package/capability history | PENDING | Admin implementation exists; production event-to-dashboard reconciliation required. |
| F-18 | Recovery, retry, concurrency | Queued/running recovery, retry policy, per-user limits and idempotency survive restart/failure | PENDING | Operational fault-injection evidence required. |

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
