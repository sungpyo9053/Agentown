# API

기본 경로는 `/api`다. 인증은 HttpOnly 세션 쿠키와 CSRF 토큰을 사용한다. 주요 리소스는 `/auth`, `/users`, `/mini-homes`, `/friendships`, `/agents`, `/designer`, `/llm-credentials`, `/harnesses`, `/executions`, `/market/products`, `/artifacts`다.

`/auth/signup`, `/auth/login`, `/auth/me` 응답은 `role`을 반환한다. 값은 `USER` 또는 `ADMIN`이며 공개 가입 요청으로 역할을 지정하거나 관리자 권한을 획득할 수 없다.

`GET /users/{handle}`은 표시 이름, 소개와 아바타만 반환하고 이메일·역할을 노출하지 않는다. `PATCH /users/me`, `PATCH /users/me/password`, `DELETE /users/me`로 프로필, 비밀번호와 탈퇴를 처리한다.

`POST /market/products`의 `official=true`는 `ADMIN`만 사용할 수 있다. 관리자는 자신이 소유하고 발행한 하네스 버전만 공식 상품으로 저장할 수 있다.

실행 생성은 `Idempotency-Key` 헤더가 필수다. `/executions/{id}/events`는 신규 이벤트 SSE이며 `/executions/{id}/history`는 화면 새로고침용 소유자 전용 이력이다. `/agents/{id}/test`는 BYOK 또는 개발용 Stub으로 단일 구성원을 검증한다. 하네스 내려받기는 ZIP, 결과 다운로드는 검증된 외부 HTTPS URL 리다이렉트다.

`GET /executions`는 본인의 최근 실행 20건을 반환한다. `POST /agents/{id}/clone`과 `GET /public/users/{handle}/harnesses`는 PUBLIC/MARKET 콘텐츠만 취급하며 복제본에는 Credential이 없다.

마켓 목록은 `query`, `order`, `category`를 지원한다. `POST /market/products/{id}/reviews`는 해당 상품 복제 이력이 있는 사용자만 1~5점 후기 한 건을 생성·수정할 수 있다.

## AI 회사 설계

- `POST /designer/companies/design`: 회사 목적 질문과 Provider·Model·Credential을 받아 검증 전 초안을 반환한다. `stubMode=true`는 API 키 없이 동일한 초안 계약을 테스트한다.
- `POST /designer/companies/validate`: 최대 5명, 순차 단계, Agent 참조, 재시도, 지원 모델, ACTIVE Credential과 위험 작업을 검사한다.
- `POST /designer/companies/apply`: 검증된 초안을 사용자 승인 후 Agent Definition과 Harness로 일괄 저장한다.

설계 응답이나 저장 스냅샷에는 API 키, `credentialId`, 사용자 입력 원문과 실행 결과를 포함하지 않는다. 실제 설계는 사용자가 소유한 ACTIVE Credential이 없으면 Provider 호출 전에 실패한다.

## 자격증명 연결 상태

`POST /llm-credentials`는 OpenAI, Anthropic 또는 Google의 실제 Models API로 연결을 확인한 뒤 성공한 키만 ACTIVE로 암호화 저장한다. `GET /llm-credentials`는 마스킹된 키, 상태와 `lastVerifiedAt`만 반환한다.
