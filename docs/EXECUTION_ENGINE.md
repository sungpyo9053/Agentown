# 실행 엔진

요청은 Idempotency-Key와 함께 PostgreSQL에 QUEUED로 저장된다. 실행 생성은 독립 Harness Validator를 통과해 발행된 불변 버전만 허용한다. 생성 시 공개 가능한 하네스 정의를 `execution_snapshot_json`에 고정하고, 자격증명 참조는 API에 노출되지 않는 별도 내부 binding에 저장한다. 실행 중에는 수정 가능한 Harness와 Agent를 다시 읽지 않는다.

Poller가 Coroutine으로 가져와 전체 20, 사용자 1, LLM 50, 외부 API 100, 다운로드 10의 제한 안에서 실행한다. 사용자당 대기는 3개다. 승인 요청과 이전 Coroutine 종료가 겹쳐도 사용자 슬롯을 해제한 직후 Queue를 다시 확인해 승인된 실행을 유실하지 않는다.

상태는 QUEUED, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED, TIMEOUT이다. 서버 시작 시 오래된 RUNNING을 QUEUED로 복구한다. 모든 시각화는 저장된 ExecutionEvent를 SSE로 전달한 결과다. 개발·테스트는 `stubMode`로 키 없이 전체 흐름을 검증할 수 있다.

Local Runner 실행에서는 Runner가 직원마다 `STEP_STARTED`, `MODEL_REQUEST_SENT`, `STEP_OUTPUT_CREATED`, `STEP_COMPLETED`를 순서대로 보고한다. 서버는 직원별 실제 출력을 `ExecutionStep`에 저장하고 다음 직원의 입력 및 실행 관제 화면에 사용한다. 전체 작업 뒤 승인이 설정된 하네스는 Runner 완료 후 `WAITING_APPROVAL`에 머물며, 사용자가 승인해야 최종 `SUCCEEDED`와 결과 다운로드가 확정된다. 현재 Local Runner MVP는 한 하네스에서 Codex 또는 Claude 한 공급자만 사용하며, 중간 승인 지점은 지원하지 않고 전체 작업 완료 후 승인만 지원한다.

발행 스냅샷에는 Validator 버전, 검증 시각, 구조 SHA-256과 각 검사 결과를 기록한다. 설계 LLM은 구조를 제안할 뿐 발행을 승인할 수 없다. 기존 발행 버전 이후 Draft를 수정해도 이미 생성된 실행과 해당 버전의 실행 결과는 변하지 않는다.
