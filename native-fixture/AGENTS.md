# CSV 변경 행 비교

## 시작 순서와 Source of truth

1. `START_HERE.md`와 이 공통 지침을 읽습니다.
2. `schemas/input.schema.json`과 `examples/sample-input.json`을 읽고 필수 입력을 확인합니다.
3. `workflow.json`을 실행 가능한 Source of truth로 사용합니다. Agent와 Guide Markdown은 파생 계약입니다.

## Objective

두 CSV 파일을 ID 기준으로 비교해서 추가 수정 삭제 행을 표로 만들어줘

## Execution flow

CSV 두 파일 입력 [manual.trigger] -> CSV 행 결정적 비교 [data.csv.compare] -> 변경 행 표 렌더링 [template.render] -> 완료 [workflow.end]

## Orchestration rules

1. Read `workflow.json`, every `agents/*.md`, and every `guides/*.md`.
2. Ask the user for every required input that is still missing. Never invent an important value.
3. Execute from `entryNodeId`, honor every dependency, and pass outputs using declared bindings and schemas.
4. Run independent nodes concurrently when the graph allows it. Run a Join successor only after every predecessor succeeded.
5. Validate each Agent input/output, final output schema, and `policies/quality-rules.json`.
6. Apply the user-confirmed Guide values to every relevant Agent output.
7. If any parallel task fails, preserve its failure and do not report the overall run as `SUCCEEDED` or execute its Join successor.
8. Stop when a decision branch has no matching edge and report the missing or failed items.
9. Pause at `human.approval`; do not treat a draft as approved without an explicit decision.
10. Do not execute arbitrary code, persist secrets, or perform undeclared external writes.
11. If an Agent, Tool, runtime, API key, or Connector is unavailable, return `EXECUTION_NOT_CONFIGURED`; never invent Mock output.
12. Preserve evidence, dates, and source URLs through every binding and in the final result when declared by the schemas.

## Failure policy

입력 형식 또는 키 열이 유효하지 않으면 비교를 중단하고 원인을 표시
