# 업무 자동화 의도 하네스 검증 보고서

- 검증일: 2026-08-24
- 입력 표본: 공개 자동화 사례에서 정리한 업무 유형 50개
- 테스트 케이스: 유형별 구체 요청 A와 모호 요청 B, 총 100개
- 검증 대상: 요구사항 판별, 에이전트 설계, 그래프 의미 일치, 승인 재개, Mock 실행 종료

## 조사 근거

업무 유형은 다음 공개 자료의 실제 자동화 범주와 예시를 참고해 구성했다.

- Zapier: 영업 리드, 콘텐츠, HR, IT 지원, 고객지원 자동화 사례
  - https://zapier.com/blog/business-process-automation-examples/
- Make: Google Sheets, Gmail 첨부, Telegram, Instagram 등 템플릿 범주
  - https://www.make.com/en/templates
- n8n: Sales, IT Ops, Marketing, Document Ops, Support 워크플로우
  - https://n8n.io/workflows/
- Microsoft Power Automate: 승인, 이메일, Forms 시나리오
  - https://learn.microsoft.com/en-us/power-automate/approvals-howto
  - https://learn.microsoft.com/en-us/power-automate/email-top-scenarios
  - https://learn.microsoft.com/en-us/power-automate/forms/popular-scenarios
- Slack, HubSpot, Asana: IT 지원, 워크플로우, 온보딩 사례
  - https://slack.com/resources/using-slack/slack-for-it-security-adoption-guide
  - https://www.hubspot.com/products/workflow-automation-guide
  - https://asana.com/resources/asana-on-asana-employee-onboarding

원본 TC와 기대 결과는 `backend/src/test/resources/builder/automation-intent-corpus.tsv`에 있다.

## 판정 구성

| 판정 | 수 | 검증 계약 |
| --- | ---: | --- |
| DESIGN | 25 | 에이전트 수, 그래프 의미 일치, 설계 승인, Mock 실행, 필요 시 사람 승인 재개, `requirementMatched=true` |
| CLARIFY | 50 | 모호한 요청을 임의 설계하지 않고 `NEEDS_CLARIFICATION`으로 중단 |
| CAPABILITY_REQUIRED | 25 | 사용자 목표는 보존하되 예약·외부 커넥터·실제 쓰기에 필요한 표준 노드/Connector를 `AUTOMATION_CAPABILITY_REQUIRED`로 명시하고 실행 차단 |

DESIGN 25건 중 단일 에이전트 설계는 14건, 2개 에이전트 설계는 11건이다.

## 결과

### 1차 기준선

- 통과: 55/100
- 실패: 45/100
- 주요 원인: FAQ 전용 4개 필수항목 강제 20건, 미지원 외부 연동 미거절 22건, 안전한 보고서 초안 오거절 2건, 모호한 뉴스 요청 조기 거절 1건

### 수정 후

- 통과: 100/100
- 실패: 0/100
- 실제 판정 분포: DESIGN 25, CLARIFY 50, CAPABILITY_REQUIRED 25
- 실행 명령: `./gradlew :backend:test --tests 'com.agentvillage.builder.AutomationIntentCorpusTest' --rerun-tasks`
- 결과 파일: `backend/build/reports/automation-intent-corpus.json`

### 실제 Codex 재검증

- 실행 모드: 실제 `codex-cli`, 모델 `gpt-5.6-luna`
- 통과: 86/100 (86%)
- 당시 기준: 70/100 이상. 최종 실행형 패키지 게이트는 별도 문서의 80/100 및 치명적 결함 0 조건으로 상향
- 기준 충족: 예
- 실행 시간: 966초
- 실패 14건: 그래프 구조·의미 불일치 9건, 모델 응답 180초 타임아웃 5건
- 결과 파일: `backend/build/reports/real-automation-intent-corpus.json`

첫 실제 호출에서 Responses API strict output schema가 `config.additionalProperties=false`를 요구해 HTTP 400으로 실패하는 운영 결함을 발견했다. 노드 설정을 허용된 닫힌 객체 조합으로 변경한 뒤 canary와 100개 전체 호출을 실행했다. 잘못된 실제 모델 그래프는 validator에서 차단됐고 실행 단계로 넘어가지 않았다.

## 수정 사항

1. Slack FAQ에 고정됐던 Mock 설계기를 요청 의미에 따라 분류, 생성, 다중 에이전트 그래프로 분기했다.
2. 직접 입력, 화면 출력, 승인 정책이 명시된 요청은 FAQ 지식원이나 외부 목적지를 추가로 묻지 않게 했다.
3. 예약 실행과 실제 외부 커넥터 쓰기는 사용자 목표를 보존한 capability blueprint로 분류하고 현재 실행은 차단하되, 보고서·이메일·소셜 문구의 초안만 만드는 요청은 허용했다.
4. 그래프 검증의 생성 의미 어휘를 분석, 추출, 번역, 교정, 보고서, 릴리스 노트까지 확장했다.
5. 범용 생성 노드의 FAQ 고정 응답을 제거하고 요청 instruction과 입력을 반영하는 Mock 결과로 바꿨다.

## 한계

이 테스트는 deterministic Meta Agent와 Mock 노드로 하네스의 계약과 제어 흐름을 검증한다. 실제 LLM 출력 품질, 실제 Slack·Notion·Gmail 등 제3자 API 연결, 예약 실행 인프라는 검증하지 않았으며 미지원 기능은 성공으로 가장하지 않고 필요한 capability를 표시한 뒤 실행 차단하는 것이 기대 동작이다.
