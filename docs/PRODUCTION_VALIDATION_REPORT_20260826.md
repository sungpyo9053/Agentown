# Agentown 업무 자동화 개발·검증서

- 기준일: 2026-08-26
- 대상 브랜치: `release/ui-agentown`
- 기준 커밋: `ab19bdc8d91b4aec9c8a8e0876e0c95b91a7cba9`
- 릴리스 태그: `automation-template-notion-v1-20260826`
- 상태: **코드·회귀 검증 통과 — 최종 운영 재배포 및 공개 E2E 진행 중**

## 1. 제품 목표

사용자는 에이전트를 직접 조립하지 않고 반복 업무를 자연어로 설명한다. 서버는 업무 계약을 추출한 뒤 가장 저렴하고 단순한 실행 전략을 선택한다.

1. 고정 규칙과 기존 템플릿으로 해결
2. 해석이 필요하면 단일 LLM 호출
3. 독립적인 판단 역할이 필요할 때만 에이전트 분리
4. 외부 쓰기와 고위험 작업은 사람 승인 포함

자동화 정의와 출력 템플릿은 별도 엔티티이며, 실행 시 승인된 `template_version_id`를 고정 참조한다.

## 2. 실행 아키텍처

```text
자연어 업무 설명
  -> 업무 계약 추출 및 누락 정보 질문
  -> 실행 전략과 출력 템플릿 선택
  -> Workflow Graph 컴파일
  -> 스키마·권한·승인 경계 검증
  -> 사용자 설계 승인
  -> Template Version 및 Workflow Version 고정
  -> 동일 렌더러로 미리보기·시뮬레이션
  -> 사용자 실행 승인
  -> 연결된 외부 서비스 실행
  -> StepRun·입출력·비용·오류 기록
```

출력 템플릿 버전은 아래 불변 집합을 고정한다.

- content schema version
- renderer version
- quality rule version
- prompt version
- model policy
- source policy version

상태 전이는 `DRAFT -> PREVIEWED -> APPROVED -> ACTIVE -> DEPRECATED`를 사용하며 승인 이후 내용 변경과 물리 삭제를 DB 트리거로 차단한다.

## 3. 구현 현황

### 완료

- Workflow Version에 `template_version_id` 및 실행 계약 스냅샷 저장
- 미리보기와 실행의 동일 고정 렌더러 경로
- 자연어 수정 시 기존 버전 복제, 비교 미리보기, 신규 버전 생성, 활성 버전 롤백
- 일일 시장 뉴스 기본 템플릿 및 JSON Schema·품질 규칙·인수 사례
- 100개 자연어 자동화 요청 판정 코퍼스
- 회의록, 보고서, 마케팅 카피, 성과 분석, 채용, 영업 제안 등 사무 자동화 시나리오
- Notion Public OAuth 앱과 운영 callback
- OAuth state 1회성, AES-GCM 토큰·리프레시 토큰 암호화, 워크스페이스 격리
- Notion 연결 상태, 해제, 읽기 검증, 401 토큰 갱신 계약
- Notion 페이지 미리보기, 명시적 승인, 1회 생성, 실패 기록 계약
- Notion OAuth `Read content`, `Update content`, `Insert content` 권한 활성화
- 페이지 쓰기 요청의 워크스페이스 격리와 비관적 잠금 기반 중복 발행 차단
- 승인 재개 시 다중 워커가 같은 실행을 중복 점유하던 결함 수정

### 진행 중

- 운영 재배포 및 공개 UI/API 검증

### 이번 릴리스 제외

- Slack 실제 이벤트 수신과 실제 전송
- 예약 실행과 장애 재시도 운영화
- 사용량 측정과 월 구독
- 오디오 전사와 CRM 실제 커넥터

## 4. 검증 증거

### 실제 실행한 자동화 검증

- 백엔드 전체 회귀: `./gradlew :backend:test` — 통과
- Notion OAuth·암호화·격리 집중 테스트 — 통과
- Notion 쓰기 미리보기·승인·중복 방지·타 워크스페이스 차단·실패 기록 집중 테스트 — 통과
- 자연어 자동화 코퍼스: 100/100 판정 계약 통과
- TypeScript: `tsc --noEmit` — 통과
- ESLint — 오류 0, 기존 폰트 경고 1
- Next.js production build — 통과, `/assemble/automation`, `/settings/connections` 포함
- Git 원격 브랜치와 annotated tag 확인
- 운영 배포 전 PostgreSQL 백업 생성

### Mock으로 검증한 범위

- LLM 구조화 출력과 허용 노드 컴파일
- Slack/Notion Mock Connector 시뮬레이션
- 사람 승인 대기와 승인 후 정확한 단계 재개
- Notion OAuth 공급자 응답, 토큰 갱신, 검색 결과는 테스트 대역 사용

### 아직 실제로 검증하지 않은 범위

- 사용자가 Notion OAuth 승인 후 실제 공유 페이지를 검색하는 E2E
- Agentown API가 실제 Notion 페이지를 생성·수정하는 E2E
- Slack 실제 전송

위 항목은 실제로 관찰하기 전까지 완료로 표시하지 않는다.

## 5. 결함 발견과 수정

전체 회귀 중 승인 재개 직후 동일 실행이 두 워커에 의해 중복 처리되었다. 동일 단계와 이벤트가 두 번 저장되면서 `uq_execution_event_sequence` 충돌로 실행이 `FAILED`가 됐다.

수정:

- 메모리 내 사용자 락만 신뢰하지 않음
- DB의 `QUEUED -> RUNNING` 조건부 UPDATE로 실행을 원자적으로 점유
- 점유 성공한 워커 하나만 Workflow를 실행
- 집중 테스트와 전체 회귀를 모두 재실행

## 6. 서비스 가능 판정

현재 판정: **운영 배포 전 최종 검증 통과**

Builder·템플릿 버전·시뮬레이션·Notion 읽기와 승인형 페이지 발행 계약은 서비스 기반 수준이다. 실제 외부 쓰기는 미리보기를 먼저 영속화하고 사용자가 별도 승인 API를 호출한 뒤에만 수행한다.

최종 통과 조건:

- Notion 외부 쓰기가 미리보기와 명시적 승인 없이 실행되지 않음
- 동일 idempotency key로 페이지가 중복 생성되지 않음
- 다른 workspace의 connection_id 사용 차단
- 시뮬레이션에서는 외부 페이지가 생성되지 않음
- 운영에서 OAuth 시작 URL과 callback 설정 확인
- 공개 UI에서 사용자 연결·대상 선택·미리보기·승인 흐름 확인
- 치명적 결함 0, 전체 회귀 통과

## 7. 사람이 수행할 일

사용자는 Agentown의 `설정 -> 업무 연결`에서 자신의 Notion 계정을 승인하고, 자동화가 접근할 대상 페이지를 선택한다. 비밀번호나 토큰을 Agentown 대화창에 입력하지 않는다.

Slack 실제 앱 설치와 이벤트·전송 승인은 다음 단계에서 진행한다.
