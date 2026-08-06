# API

기본 경로는 `/api`다. 인증은 HttpOnly 세션 쿠키와 CSRF 토큰을 사용한다. 주요 리소스는 `/auth`, `/users`, `/mini-homes`, `/friendships`, `/agents`, `/llm-credentials`, `/harnesses`, `/executions`, `/market/products`, `/artifacts`다.

`/auth/signup`, `/auth/login`, `/auth/me` 응답은 `role`을 반환한다. 값은 `USER` 또는 `ADMIN`이며 공개 가입 요청으로 역할을 지정하거나 관리자 권한을 획득할 수 없다.

`POST /market/products`의 `official=true`는 `ADMIN`만 사용할 수 있다. 관리자는 자신이 소유하고 발행한 하네스 버전만 공식 상품으로 저장할 수 있다.

실행 생성은 `Idempotency-Key` 헤더가 필수다. `/executions/{id}/events`는 신규 이벤트 SSE이며 `/executions/{id}/history`는 화면 새로고침용 소유자 전용 이력이다. `/agents/{id}/test`는 BYOK 또는 개발용 Stub으로 단일 구성원을 검증한다. 하네스 내려받기는 ZIP, 결과 다운로드는 검증된 외부 HTTPS URL 리다이렉트다.
