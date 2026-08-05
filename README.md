# Agentown

사람 형태의 AI 구성원을 만들고 미니홈에 배치하는 소셜 AI 에이전트 플랫폼의 MVP입니다.

## 구성

- `backend`: Kotlin, Spring Boot, Spring Modulith, PostgreSQL, Flyway
- `frontend`: Next.js, React, TypeScript, Tailwind CSS
- `docs/IMPLEMENTATION_PLAN.md`: MVP 구현 계획과 완료 기준
- `SPEC.md`: 제품 요구사항의 단일 기준

## 로컬 실행

```bash
cp .env.example .env
docker compose up --build
```

Docker Compose는 다른 로컬 PostgreSQL과 충돌하지 않도록 PostgreSQL을
기본 `localhost:5433`에 노출합니다. 필요하면 `POSTGRES_PORT`로 변경할 수
있으며, 컨테이너 내부 연결은 계속 `postgres:5432`를 사용합니다.

- Web: http://localhost:3000
- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health

`.env.example`의 마스터 키는 로컬 개발 전용입니다. 운영 배포 전 `openssl rand -base64 32`로 새 키를 만들고 DB와 분리해 보관하세요. 실제 LLM 실행은 플랫폼 공용 키가 아니라 사용자가 `/settings/credentials`에서 등록한 BYOK 자격증명만 사용합니다.

Docker Desktop을 사용하지 않는 경우 PostgreSQL을 먼저 실행한 뒤 다음처럼 각 앱을 시작할 수 있습니다.

```bash
export LLM_MASTER_KEY="$(openssl rand -base64 32)"
./gradlew :backend:bootRun
npm --prefix frontend ci
npm --prefix frontend run dev
```

## 테스트

```bash
./gradlew :backend:test
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run build
```

Docker가 가능한 환경에서는 통합 테스트가 Testcontainers PostgreSQL을 자동으로 사용합니다. 로컬 PostgreSQL을 쓰려면 `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`, `TEST_DATABASE_PASSWORD`를 지정하세요.

## 맥에서 디버깅

Xcode는 필요하지 않습니다. Docker Desktop, JDK 17, Node.js 20 이상과
IntelliJ IDEA 또는 VS Code를 사용하면 됩니다. 빠른 코드 디버깅에서는
PostgreSQL만 Docker로 실행하고 애플리케이션은 호스트에서 실행하세요.

```bash
cp .env.example .env
docker compose stop backend frontend
docker compose up -d postgres

export DB_URL=jdbc:postgresql://localhost:5433/agent_village
export DB_USERNAME=agent_village
export DB_PASSWORD=agent_village_local
export LLM_MASTER_KEY=VGhpcy1pcy1hLXRlc3Qta2V5LWZvci1hZXMtMjU2ISE=

# 일반 실행
./gradlew :backend:bootRun

# 또는 JVM 디버거가 5005 포트에 연결될 때까지 대기
./gradlew :backend:bootRun --debug-jvm
```

다른 터미널에서 프론트엔드를 실행합니다.

```bash
npm --prefix frontend run dev
```

IntelliJ에서는 `localhost:5005`에 Remote JVM Debug로 연결합니다. 프론트엔드는
Chrome 개발자 도구 또는 VS Code JavaScript Debugger를 사용하면 됩니다.
컨테이너 로그와 상태는 다음 명령으로 확인합니다.

```bash
docker compose ps
docker compose logs -f backend frontend postgres
```

VS Code에서는 저장소에 포함된 `Full Stack: Debug` 구성을 선택하고 `F5`를
누르면 PostgreSQL 준비, Kotlin/Spring 디버거, Next.js 개발 서버와 Chrome
디버거가 함께 시작됩니다. 자세한 내용은
[`docs/VS_CODE_DEBUGGING.md`](docs/VS_CODE_DEBUGGING.md)를 참고하세요.

## MVP 기능

- 세션/CSRF 기반 회원가입·로그인과 미니홈·일촌·차단
- 벽·바닥·책상·회의 공간이 있는 회사형 미니홈, 사람형 캐릭터 5종, 드래그 배치
- Agent CRUD, Provider/Model 선택, Agent MD·Guide·Schema 자동 생성
- AES-256-GCM BYOK 자격증명과 소유권/Provider/Model 사전 검증
- 최대 5개 Agent의 순차 하네스 연결·검증·불변 발행·복제·ZIP
- PostgreSQL Queue, Coroutine 내부 Worker, 작업별 동시성 제한, SSE 이벤트
- 실제 단계 이벤트에 따른 캐릭터 작업 상태와 담당 순서 이동
- 실행자 전용 결과와 외부 HTTPS 다운로드, 무료 마켓 기반
- 외부 API 키 없이 전체 흐름을 검증하는 Stub 실행

사용자 Python/Node/Shell, 임의 패키지, Dockerfile과 바이너리는 실행하지 않습니다.
