# 실행 엔진

요청은 Idempotency-Key와 함께 PostgreSQL에 QUEUED로 저장된다. Poller가 Coroutine으로 가져와 전체 20, 사용자 1, LLM 50, 외부 API 100, 다운로드 10의 제한 안에서 실행한다. 사용자당 대기는 3개다.

상태는 QUEUED, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED, TIMEOUT이다. 서버 시작 시 오래된 RUNNING을 QUEUED로 복구한다. 모든 시각화는 저장된 ExecutionEvent를 SSE로 전달한 결과다. 개발·테스트는 `stubMode`로 키 없이 전체 흐름을 검증할 수 있다.
