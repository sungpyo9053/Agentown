# 아키텍처

Next.js Web → Spring Boot 모듈러 모놀리스 → PostgreSQL 및 외부 HTTPS API 구조다. 모듈은 identity, profile, social, minihome, agent, harness, execution, llmcredential, marketplace, artifact, common으로 나뉜다.

Controller는 Application Service만 호출한다. 모듈 간 접근은 공개 인터페이스로 제한한다. 실행은 Coroutine과 작업별 Semaphore를 사용하며 PostgreSQL Queue에서 복구한다. Kafka, Kubernetes, 별도 Worker는 사용하지 않는다.
