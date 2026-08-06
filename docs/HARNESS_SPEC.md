# 선언형 하네스 명세

MVP는 `LLM`, `EXTERNAL_API`, `DOWNLOAD`, `APPROVAL` 단계와 성공 연결, 최대 3회 재시도, 최대 5개 에이전트를 허용한다. 자유 반복과 병렬 실행은 금지한다.

발행 스냅샷에는 역할, 작업, 가이드, 스키마, 실행 순서, 추천 Provider/Model, 비밀이 아닌 옵션만 포함한다. `credentialId`, API 키, 토큰, 조직·프로젝트 ID, 사용자 입력과 실행 결과는 포함하지 않는다.

검증기는 시작 단계, 연속 순서, Agent 존재·소유권, Edge 대상, 재시도·단계 제한을 검사한다.

## 기준 패키지

```text
ai-company/
├── README.md
├── AGENTS.md
├── CLAUDE.md
├── harness.md
├── harness.json
├── agents/
├── guides/
└── schemas/
```

`AGENTS.md`와 `CLAUDE.md`는 같은 목표, 단계, 승인·실패 정책을 각 CLI가 발견할 수 있는 이름으로 제공한다. 실제 기계 실행 계약은 `harness.json`과 schema이며 자격증명, 사용자 입력과 실행 결과는 패키지에 포함하지 않는다.

## 설계 초안 계약

설계 LLM은 최대 5개 Agent를 포함하는 구조화 초안만 반환한다. 각 Agent에는 `key`, `name`, `role`, `responsibility`, `taskDescription`, `desiredOutput`, `requiredEvidence`, `guide`, `prohibitions`, `rewriteCriteria`, `approvalCriteria`, 추천 Provider·Model이 필요하다. 서버는 키 중복, 빈 역할, 단계·재시도 제한, 존재하지 않는 Agent 참조와 위험한 작업 문구를 검사한다. 검증을 통과한 초안도 사용자가 승인하기 전에는 영속 하네스로 만들지 않는다.
