# 아키텍처

Next.js Web → Spring Boot 모듈러 모놀리스 → PostgreSQL 및 외부 HTTPS API 구조다. 모듈은 identity, profile, social, minihome, agent, harness, execution, llmcredential, marketplace, artifact, common으로 나뉜다.

Controller는 Application Service만 호출한다. 모듈 간 접근은 공개 인터페이스로 제한한다. 실행은 Coroutine과 작업별 Semaphore를 사용하며 PostgreSQL Queue에서 복구한다. Kafka, Kubernetes, 별도 Worker는 사용하지 않는다.

미니홈은 회사 공간을 시각적 메타포로 사용하되 실행 순서는 캐릭터 좌표로 판단하지 않는다. 저장 좌표는 꾸미기 전용이며, 실행 화면은 오케스트레이터가 발행한 SSE 이벤트의 `agentId`와 단계 순서로 캐릭터 상태 및 이동 목표를 결정한다.
