# 아키텍처

Next.js Web → Spring Boot 모듈러 모놀리스 → PostgreSQL 및 외부 HTTPS API 구조다. 모듈은 identity, profile, social, minihome, agent, harness, execution, llmcredential, marketplace, artifact, common으로 나뉜다.

Controller는 Application Service만 호출한다. 모듈 간 접근은 공개 인터페이스로 제한한다. 실행은 Coroutine과 작업별 Semaphore를 사용하며 PostgreSQL Queue에서 복구한다. Kafka, Kubernetes, 별도 Worker는 사용하지 않는다.

API 경계에는 로그인·휴대폰 인증·설계·실행별 고정 윈도우 Rate Limit이 있고, 프록시 환경에서만 명시적으로 전달 IP를 신뢰한다. 실행 Queue, 완료 결과와 소요 시간은 Actuator 메트릭으로 노출한다. 외부 Provider URL은 환경변수로 분리되어 운영 endpoint와 로컬 계약 서버를 같은 Gateway 구현으로 검증한다.

미니홈은 회사 공간을 시각적 메타포로 사용하되 실행 순서는 캐릭터 좌표로 판단하지 않는다. 저장 좌표는 꾸미기 전용이며, 실행 화면은 오케스트레이터가 발행한 SSE 이벤트의 `agentId`와 단계 순서로 캐릭터 상태 및 이동 목표를 결정한다.

캐릭터 프리셋은 업무 역할이 아니라 교체 가능한 외형이다. 에이전트 이름·역할·책임은 자유 입력이며 회계, 법무, 상담, 분석 등 프리셋 이름에 없는 역할도 같은 실행 계약으로 동작한다. 하네스 결과 역시 고정 글 형식이 아니라 결과 담당 단계와 MIME 성격을 선언하는 동적 계약이다.

운영 배포는 한 서버의 Docker Compose를 유지한다. Caddy만 80/443을 공개하며 Next.js, Spring Boot, PostgreSQL은 내부 네트워크에 둔다. SMS는 `SmsGateway` 포트 뒤 Stub 또는 HTTPS Webhook 구현을 선택하므로 공급자 계약이 플랫폼 도메인에 침투하지 않는다.
