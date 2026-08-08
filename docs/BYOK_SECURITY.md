# BYOK 보안

플랫폼 공용 키를 사용하지 않는다. 사용자 키는 임의 96-bit nonce의 AES-256-GCM으로 인증 암호화하며 키 버전을 함께 저장한다. 마스터 키는 DB 밖 환경변수에 두고 `SecretEncryptor` 포트로 KMS 교체를 허용한다.

키 원문은 등록 응답·조회·로그·하네스·실행 이력에 나타나지 않는다. 복호화된 `CharArray`는 호출 범위가 끝나면 덮어쓴다. 실행 전 소유자, ACTIVE 상태, Provider와 지원 Model 일치를 확인한다.

자격증명 저장 요청은 Provider의 실제 Models API 연결 검증을 먼저 통과해야 한다. 화면의 `연결 완료`는 DB 저장 성공만 뜻하지 않고 Provider가 해당 키를 수락했으며 `lastVerifiedAt`이 기록된 ACTIVE 상태를 뜻한다.

AI 회사 설계도 동일한 BYOK 정책을 적용한다. OpenAI 설계는 Responses API, Claude 설계는 Anthropic Messages API를 사용한다. 설계 LLM의 JSON은 신뢰하지 않고 서버 검증 후 사용자 승인 단계에서만 Agent와 Harness로 저장한다. 웹 서버는 Codex CLI, Claude Code, Shell이나 사용자 코드를 실행하지 않는다.

OpenAI Responses, Anthropic Messages, Google generateContent와 각 Models 검증 endpoint는 로컬 HTTP 계약 테스트에서 header, 요청 본문, 응답·토큰 파싱을 검증한다. 키 자체는 테스트 실패 메시지나 애플리케이션 로그에 기록하지 않는다. 운영에서는 `LLM_MASTER_KEYS` 키링에 현재·이전 키 버전을 함께 제공해 기존 데이터를 복호화하면서 새 버전으로 회전할 수 있다.
