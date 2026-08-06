# Agentown MVP 구현 계획 및 현황

## 목표

`SPEC.md`를 단일 기준으로 질문형 AI 회사 설계부터 웹 실행, 공유와 로컬 CLI 호환 내보내기까지 이어지는 MVP를 제공한다. 실제 블로그 운영 하네스의 구조를 범용 설계 기준으로 사용하며 별도 Worker 서버 없이 Spring Boot 프로세스 안의 Coroutine Worker를 사용한다.

## 최상위 제품 흐름

1. 사용자가 회사 목적, 입력, 결과, 근거, 금지사항과 승인 조건에 답한다.
2. 사용자가 연결한 OpenAI 또는 Anthropic BYOK 자격증명과 설계 모델을 선택한다.
3. 설계 모델이 최대 5개 구성원, 가이드, 스키마와 순차 실행 초안을 생성한다.
4. 서버 검증기가 구조·안전·단계·재시도 제한을 검사한다.
5. 사용자가 미리보기를 승인하면 Agent와 Harness를 자신의 회사에 저장한다.
6. 웹에서는 Provider API로 실행하고 SSE 이벤트를 캐릭터 상태와 연결한다.
7. 내려받기에는 Codex용 `AGENTS.md`, Claude Code용 `CLAUDE.md`와 공통 선언형 파일을 포함한다.

## 저장소 구성

```text
.
├── backend/                 Kotlin/Spring Boot 모듈러 모놀리스
├── frontend/                Next.js App Router 애플리케이션
├── docs/                    구현 및 운영 문서
├── docker-compose.yml       Web/API/PostgreSQL 로컬 스택
└── SPEC.md                  제품 요구사항 단일 기준
```

## 구현 순서와 완료 기준

### 1. 기반 구축

- Gradle Kotlin DSL과 Spring Boot 애플리케이션 구성
- identity, minihome, agent, common 모듈 패키지 경계
- 예외 응답, Bean Validation, Spring Security 세션 인증
- PostgreSQL/Flyway 스키마 및 Testcontainers 통합 테스트 환경
- Actuator 상태 점검과 GitHub Actions CI

완료 기준: 애플리케이션 컨텍스트와 PostgreSQL 마이그레이션이 기동되고 전체 백엔드 테스트가 통과한다.

### 2. 미니홈

- 가입 시 프로필과 미니홈 자동 생성
- 본인 미니홈 조회·수정
- 공개 handle 조회와 공개 범위 검사
- 0~1 정규화 좌표를 사용하는 room item 일괄 저장
- 낙관적 잠금으로 동시 수정 보호

완료 기준: 가입 사용자가 미니홈을 편집하고 공개 미니홈을 비로그인 상태에서 조회할 수 있으며 PRIVATE 미니홈은 차단된다.

### 3. 에이전트

- 소유자 기준 Agent CRUD
- 이름, 역할, 성격, 캐릭터, 시스템 프롬프트, 스크립트, 가이드, 모델, 파라미터, 공개 범위
- BYOK 자격증명 소유권 확인과 provider 일치 검증
- 미니홈 배치용 Agent room item 연결
- 목록 및 단건 조회, 입력 검증, 소유권 검증

완료 기준: 인증 사용자가 에이전트를 생성·수정·삭제하고 본인 목록을 조회할 수 있으며 다른 사용자의 비공개 데이터에 접근할 수 없다.

### 3A. BYOK 자격증명 기반

- `LlmCredential` 도메인과 등록·목록·삭제·검증 API
- AES-256-GCM `SecretEncryptor` 구현, 무작위 nonce, 인증 태그, 키 버전
- 환경변수 키링과 향후 AWS KMS 구현을 위한 포트 분리
- 원문 비반환, 마스킹, 로그 비노출
- `AiModelGateway`와 provider Registry, 실행 전 자격증명 검증 서비스
- 하네스 스냅샷용 비밀정보 제거 정책 테스트

완료 기준: DB에 평문 키가 없고 다른 사용자 자격증명 연결 및 provider 불일치가 차단되며, 자격증명 삭제/폐기 후 실행 전 검증이 실패한다.

### 3B. AI 회사 설계기

- 질문형 `CompanyDesignRequest`와 구조화 `CompanyDesignDraft`
- Stub 설계기와 OpenAI/Anthropic Provider API 설계기
- 사용자 소유 ACTIVE Credential 및 Provider·Model 일치 검증
- 최대 5개 Agent, 순차 실행, 제한된 재시도, 금지 작업 검사
- 초안 미리보기 후 승인 시 Agent·Definition·Harness 일괄 생성
- 설계 단계와 저장 단계를 분리해 LLM 출력을 그대로 영속·실행하지 않음

완료 기준: API 키 없이 Stub으로 전체 설계·승인을 통합 테스트할 수 있고, 실제 경로는 사용자의 ACTIVE 자격증명이 없으면 시작되지 않는다.

### 4. 회사형 미니홈 Web UI

- 랜딩, 가입, 로그인, 대시보드
- 벽, 창문, 바닥, 책상, 회의 공간, 가구를 갖춘 회사형 미니홈
- 사람 형태 직군 캐릭터 5종, 드래그 배치, 0~1 좌표 저장, 3개 오피스 테마
- 실제 SSE 이벤트와 캐릭터 작업 상태·오케스트레이션 이동 연결
- 미니홈 정보 편집 및 에이전트 생성·목록·편집
- API 오류와 로그인 상태 처리
- 하네스 연결·검증·발행·Stub 실행, 일촌, BYOK, 무료 마켓 화면

완료 기준: Next.js lint와 production build가 통과하고 Docker Compose로 브라우저에서 접근 가능하다.

### 5. 서버 결정 전 로컬 완결 범위

- 공개 프로필 개인정보 최소화와 프로필·비밀번호·탈퇴
- 비공개 발행 하네스의 UUID 추측 복제·다운로드·타인 마켓 등록 차단
- PUBLIC/MARKET 에이전트와 하네스의 Credential 없는 복제
- 마켓 카테고리, 무료 게시, 복제 이력과 복제 사용자 후기
- 최근 실행 목록과 오피스 대시보드 연결
- Flyway V7과 공유 권한 통합 테스트

완료 기준: 외부 서버, SMS 계약, KMS 계정 없이 Docker Compose에서 가입부터 설계·실행·공유·복제·후기까지 검증할 수 있다.

## 아키텍처 결정

- 초기에는 단일 Spring Boot 배포와 PostgreSQL만 사용한다.
- 인증은 서버 세션 쿠키를 사용한다. 쿠키는 HttpOnly이며 운영 프로필에서 Secure를 적용한다.
- 프론트엔드의 상태 변경 요청은 same-site 배포를 전제로 하고 CSRF 토큰 쿠키/헤더 패턴을 사용한다.
- JPA 엔티티와 Repository는 각 기능 모듈 내부에 둔다. 외부 모듈에는 Application Service와 읽기 포트만 공개한다.
- 미니홈 아이템의 `positionX`, `positionY`, `width`, `height`는 0~1 범위를 DB 제약과 애플리케이션 검증으로 함께 보장한다.
- LLM 마스터 키는 `LLM_MASTER_KEY` 환경변수로만 주입하며 데이터베이스에 저장하지 않는다. 테스트 키는 테스트 프로필에만 둔다.
- 미니홈 저장 위치와 실행 중 위치는 분리한다. 실행 중 이동은 `STEP_STARTED`, `MODEL_REQUEST_SENT`, `STEP_OUTPUT_CREATED`, `STEP_COMPLETED` 이벤트가 발생했을 때만 갱신한다.

## 검증 계획

1. `./gradlew :backend:test`
2. `npm --prefix frontend ci`
3. `npm --prefix frontend run lint`
4. `npm --prefix frontend run build`
5. `docker compose config`
6. `docker compose up --build` 후 health API와 브라우저 HTTP 응답 확인

## 후속 범위

결제·정산, 미니홈 유료 아이템, 병렬 실행, 자유 반복 루프, 공동 편집, 물리 Worker 분리는 실제 사용 지표가 확인된 뒤 진행한다.
