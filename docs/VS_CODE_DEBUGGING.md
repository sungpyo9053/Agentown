# VS Code debugging on macOS

Agentown의 백엔드와 프론트엔드를 VS Code 하나에서 동시에 디버깅하는
절차다. Xcode는 필요하지 않다.

## 준비물

- Docker Desktop
- JDK 17 x64
- Node.js 20 이상 x64
- VS Code
- 저장소가 추천하는 VS Code 확장

VS Code에서 저장소를 열면 추천 확장 설치 알림이 나타난다. 알림이 보이지
않으면 Extensions 화면에서 `@recommended`를 검색해 설치한다.

## 최초 준비

터미널에서 프론트엔드 의존성을 한 번 설치한다.

```bash
npm --prefix frontend install
```

Docker Desktop이 `running`인지 확인한다.

```bash
docker desktop status
```

## 전체 스택 디버깅

1. VS Code에서 Agentown 저장소 루트를 연다.
2. 왼쪽 `Run and Debug`를 연다.
3. 상단 구성에서 `Full Stack: Debug`를 선택한다.
4. `F5`를 누른다.

VS Code는 자동으로 다음 작업을 수행한다.

1. Docker에서 실행 중인 Agentown 백엔드와 프론트엔드를 중지한다.
2. PostgreSQL만 `localhost:5433`에서 유지한다.
3. Spring Boot를 JVM 디버그 포트 `5005`로 실행한다.
4. Next.js 개발 서버를 `localhost:3000`에서 실행한다.
5. Chrome을 열고 프론트엔드 디버거를 연결한다.

Kotlin 중단점은 `backend/src/main/kotlin`, TypeScript 중단점은
`frontend` 아래 소스 파일에 설정한다.

## 개별 디버깅

- 백엔드만: `Backend: Kotlin/Spring`
- 프론트엔드만: `Frontend: Next.js/Chrome`

## 테스트와 로그

`Terminal > Run Task`에서 다음 작업을 실행할 수 있다.

- `backend: test`
- `frontend: lint and typecheck`
- `Docker: show logs`

## 전체 Docker 실행으로 복귀

디버깅을 종료한 뒤 터미널에서 다음을 실행한다.

```bash
export LLM_MASTER_KEY=VGhpcy1pcy1hLXRlc3Qta2V5LWZvci1hZXMtMjU2ISE=
docker compose up -d
```

위 키는 저장소의 로컬 개발 전용 키다. 실제 사용자 BYOK 키와 운영 마스터
키로 사용하지 않는다.
