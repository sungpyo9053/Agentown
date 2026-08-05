# API

기본 경로는 `/api`다. 인증은 HttpOnly 세션 쿠키와 CSRF 토큰을 사용한다. 주요 리소스는 `/auth`, `/users`, `/mini-homes`, `/friendships`, `/agents`, `/llm-credentials`, `/harnesses`, `/executions`, `/market/products`, `/artifacts`다.

실행 생성은 `Idempotency-Key` 헤더가 필수다. `/executions/{id}/events`는 SSE다. 하네스 내려받기는 ZIP, 결과 다운로드는 검증된 외부 HTTPS URL 리다이렉트다.
