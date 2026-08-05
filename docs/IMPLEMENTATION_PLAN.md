# Agent Village 1~3단계 구현 계획

## 목표

`SPEC.md`의 1~3단계 범위에 맞춰 실행 가능한 모노레포 기반, 인증, 미니홈, 에이전트 관리, 데이터베이스 마이그레이션, 기본 Web UI를 제공한다.

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

### 4. 기본 Web 화면

- 랜딩, 가입, 로그인, 대시보드
- 미니홈 정보 편집 및 에이전트 생성·목록
- API 오류와 로그인 상태 처리
- 반응형 캐릭터 카드 기반 UI

완료 기준: Next.js lint와 production build가 통과하고 Docker Compose로 브라우저에서 접근 가능하다.

## 아키텍처 결정

- 초기에는 단일 Spring Boot 배포와 PostgreSQL만 사용한다.
- 인증은 서버 세션 쿠키를 사용한다. 쿠키는 HttpOnly이며 운영 프로필에서 Secure를 적용한다.
- 프론트엔드의 상태 변경 요청은 same-site 배포를 전제로 하고 CSRF 토큰 쿠키/헤더 패턴을 사용한다.
- JPA 엔티티와 Repository는 각 기능 모듈 내부에 둔다. 외부 모듈에는 Application Service와 읽기 포트만 공개한다.
- 미니홈 아이템의 `positionX`, `positionY`, `width`, `height`는 0~1 범위를 DB 제약과 애플리케이션 검증으로 함께 보장한다.
- LLM 마스터 키는 `LLM_MASTER_KEY` 환경변수로만 주입하며 데이터베이스에 저장하지 않는다. 테스트 키는 테스트 프로필에만 둔다.

## 검증 계획

1. `./gradlew :backend:test`
2. `npm --prefix frontend ci`
3. `npm --prefix frontend run lint`
4. `npm --prefix frontend run build`
5. `docker compose config`
6. `docker compose up --build` 후 health API와 브라우저 HTTP 응답 확인

## 후속 범위

일촌 전체 상태 전이, 실제 AI 공급자 호출, 하네스/실행/SSE, 무료 마켓은 4단계 이후에 구현한다. 현재 데이터와 모듈 경계는 이 확장을 수용하되 미사용 인프라는 선행 도입하지 않는다.
