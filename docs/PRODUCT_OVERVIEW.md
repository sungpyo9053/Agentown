# Agentown 제품 개요

Agentown은 사용자가 쉬운 질문에 답하면 AI가 필요한 구성원, 가이드, 스키마와 실행 순서를 설계해 주는 소셜 AI 회사 플랫폼이다. 실제 운영 중인 블로그 하네스의 `최상위 오케스트레이션 + agents + guides + schemas` 구조를 범용 설계 원리로 사용한다.

핵심 흐름은 `목적 인터뷰 → BYOK 설계 모델 → AI 회사 초안 → 서버 검증 → 사용자 승인 → 캐릭터 오피스 → 실행 → 공유·복제·내려받기`다. 사용자는 스크립트, Markdown이나 JSON을 처음부터 직접 작성하지 않는다.

웹 실행은 사용자 OpenAI·Anthropic·Google API 키를 사용하는 Provider API 호출이며, Agentown 서버는 Codex CLI·Claude Code나 Shell을 실행하지 않는다. 내려받은 패키지는 동일한 실행 정의를 참조하는 `AGENTS.md`와 `CLAUDE.md`를 함께 제공해 사용자의 로컬 Codex CLI와 Claude Code에서 열 수 있다. 실행 결과는 실행자 개인 소유이며 공유 대상이 아니다.

MVP는 회원, 일촌, 미니홈, 최대 5개 에이전트의 순차 실행, BYOK, 무료 마켓을 제공한다. 사용자 코드·패키지·컨테이너 실행은 지원하지 않는다.
