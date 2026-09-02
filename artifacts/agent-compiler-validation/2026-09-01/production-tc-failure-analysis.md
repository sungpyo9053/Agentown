# 운영 TC-01~10 실패 분석

## 수정 전 증거

- 운영 직접 검증: PASS 0, PARTIAL 1, FAIL 9.
- 로컬 고정 회귀 테스트 최초 실행: `AgentCompilerUserAcceptanceTest` 7건 중 6건 실패.
- 실패 항목: FAQ 근거 분기, 뉴스 Renderer, 경쟁사 병렬 계약, GitHub 분류, PeopleMagic unresolved, 항공권 안전 계약.
- 성공 항목: CSV 결정적 비교 라우팅 1건. 단, 운영에서는 아직 이전 revision이라 실패했다.

## 실제 생성 경로

1. API: `BuilderController.message` → 비동기 `BuilderGenerationJobService` → `BuilderService.sendMessage`.
2. Requirement/LLM DTO: `CodexCliMetaAgentModel.prompt` → `LlmMetaAgentDesignDto`.
3. 서버 정규화: `StructuredMetaAgentPipeline.normalize`.
4. 카탈로그 해석: `BuilderCapabilityResolver.resolve`.
5. Agent/Function 표현: `AgentDesignAssembler.assemble`.
6. Graph 컴파일/검증: `BuilderService.compileGraph` → `WorkflowGraphValidator.validate`.
7. Version 저장: `BuilderService.saveVersion`.
8. 캔버스: `/api/builder/workflows/{id}`의 서버 `WorkflowVersion`을 `assemble/automation/page.tsx`가 렌더링.
9. Mock 실행: `BuilderService.executeFrom` + `WorkflowNodeCatalog`.
10. Package: `BuilderService.harnessPackage` → `HarnessPackageRenderer` → `BuilderController.downloadPackage` ZIP.

## 근본 원인

- `StructuredMetaAgentPipeline.normalize`의 명시적 계약 컴파일러가 FAQ, CSV, 글쓰기, 뉴스 일부에만 존재했다. 나머지는 모델의 범용 그래프를 그대로 유지해 FAQ/승인 fallback 편향이 남았다.
- `BuilderMvpSupportPolicy`가 읽기 전용 GitHub 이슈까지 `github` 문자열 하나로 배포/쓰기 요청으로 차단했다.
- `WorkflowGraphPlan`은 문자열 condition만 보유했고, 모델이 `success` 또는 잘못된 비교식을 반환하면 실행 계약까지 오염됐다.
- `rendererKey` 선택이 모델 기억에 의존했고 기존 저장 설계에 대한 복구 경로가 없었다.
- `WorkflowNodeCatalog`의 Mock AI가 instruction과 입력을 문자열로 이어 붙였고 `finishRun`은 업무 결과 검증 없이 무조건 `SUCCEEDED`로 기록했다.
- FAQ fixture가 근거 있음 한 종류뿐이라 근거 없음 계약을 검증할 수 없었다.
- 자연어 Patch는 승인 추가와 Slack→Email 일부만 지원해 Schedule 변경과 CSV 요약 단계 삽입을 처리하지 못했다.
- Package API/UI는 후보 코드에 존재했지만 운영 revision에는 배포되지 않았다.

## 중간 산출물 보존 방식

- Requirement Spec: `AutomationRequirement`와 `builder_requirements.structured_json`.
- Tool/Connector Resolution: `AutomationProposal.resourcePlan`.
- Agent Plan: `BuilderProposalEntity.agentDefinitionsJson`.
- Raw Graph: 모델 DTO의 `proposal.graphPlan`.
- Normalized Graph: `WorkflowGraphPlanNormalizer.normalize` 이후 `proposal.graphPlan`.
- Validation Result: `WorkflowValidationResult`, Snapshot API와 Version hash에 결속.

이 문서는 수정 전 실패 기준선이다. 후속 성공 결과로 덮어쓰지 않는다.
