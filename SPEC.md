너는 시니어 Kotlin/Spring Boot 아키텍트이자 Next.js 풀스택 개발자다.

현재 저장소에 Agentown이라는 소셜형 AI 에이전트 하네스 플랫폼의 MVP를 설계하고 구현하라.

사용자에게 중간 확인을 요청하지 말고 저장소를 먼저 분석한 뒤, 합리적인 기본값을 선택하여 구현하라.

단, 요구사항에 없는 마이크로서비스, Kubernetes, Kafka, GPU 서버, 사용자별 VM, 코드 샌드박스, 복잡한 분산 시스템을 임의로 도입하지 마라.

1. 서비스 정의

Agentown은 사용자가 AI 에이전트를 사람 형태의 캐릭터로 만들고, 각 에이전트에 이름·역할·가이드·입력·출력·사용 모델을 지정한 뒤 서로 연결하여 실행 가능한 하네스를 만드는 플랫폼이다.

사용자는 자신이 만든 AI 팀을 미니홈 형태로 꾸밀 수 있다.

다른 사용자의 미니홈을 방문하고, 공개된 에이전트와 하네스를 구경하고, 복제하고, 내려받을 수 있어야 한다.

초기에는 무료 공유와 복제를 제공하며, 이후 유료 하네스 판매와 미니홈 꾸미기 아이템 판매로 확장한다.

Agentown의 핵심 가치는 다음과 같다.

* CLI, Git, Docker, JSON, 환경 설정 없이 AI 하네스를 만들 수 있다.
* 사용자는 사람을 추가하듯 에이전트를 추가한다.
* 플랫폼이 사용자 입력을 바탕으로 에이전트 MD와 가이드를 자동 생성한다.
* 플랫폼이 여러 에이전트의 연결 구조와 실행 규칙을 자동 생성한다.
* 실행 과정은 캐릭터가 실제로 협업하는 것처럼 시각적으로 표시한다.
* 하네스는 공유되지만 실행 결과물은 실행한 사용자 개인에게만 귀속된다.

1.1 기준 하네스와 핵심 제품 흐름

Agentown의 기준 하네스는 실제 운영 중인 선언형 블로그 자동화 구조다. 특정 블로그의
문구를 복사하는 것이 아니라 다음 설계 원리를 모든 업종의 AI 회사에 일반화한다.

* 최상위 오케스트레이션 문서가 전체 목표, 실행 순서, 전달 규칙과 승인 경계를 정의한다.
* `agents/`는 구성원별 역할, 책임, 입력, 출력, 완료·실패 조건을 관리한다.
* `guides/`는 여러 구성원에게 반복 적용되는 품질, 사실 검증, 말투와 금지 정책을 관리한다.
* `schemas/`는 단계 사이에 전달되는 입력과 출력 계약을 관리한다.
* 실행 결과는 정의 파일과 분리하며 하네스 제작자에게 공개하지 않는다.

제품의 최상위 사용자 흐름은 다음과 같다.

사용자 목적 인터뷰
→ 사용자 BYOK 설계 모델 선택
→ AI 회사 전체 초안 생성
→ 선언형 하네스 검증
→ 사용자 검토 및 승인
→ 에이전트·가이드·스키마·연결 구조 저장
→ 캐릭터 오피스 배치
→ BYOK 실행 및 SSE 관제
→ 공유·복제·표준 패키지 내려받기

설계 모델은 사용자가 연결한 OpenAI 또는 Anthropic 자격증명을 사용한다. 로컬 및
자동화 테스트에서는 Stub 설계 모델을 사용할 수 있다. 플랫폼 공용 API 키를 기본
경로로 사용하지 않는다.

내보낸 패키지는 다음 두 로컬 AI 도구와 호환되어야 한다.

* Codex CLI는 프로젝트 루트의 `AGENTS.md`를 읽는다.
* Claude Code는 프로젝트 루트의 `CLAUDE.md`를 읽는다.

두 파일은 같은 하네스 목표와 안전 정책을 표현하며 `harness.json`, `agents/`,
`guides/`, `schemas/`를 공통 실행 정의로 참조한다. Agentown 웹 내부에서는 CLI나
Shell을 실행하지 않고 Provider API를 직접 호출한다.

2. 핵심 제품 철학

Agentown은 코드를 실행하는 플랫폼이 아니다.

사용자가 업로드하거나 판매하는 것은 Python, Node.js, Shell Script, Dockerfile이 아니라 선언형 하네스 정의다.

하네스에는 다음 정보만 포함한다.

* 에이전트 이름
* 역할
* 작업 설명
* 가이드
* 금지사항
* 입력 구조
* 출력 구조
* 연결 관계
* 실행 순서
* 실패 및 재시도 정책
* 사용자 승인 지점
* 사용할 외부 서비스 종류
* 추천 모델 설정

다음은 지원하지 않는다.

* 사용자 Python 코드 실행
* 사용자 Node.js 코드 실행
* Shell 명령 실행
* npm install
* pip install
* Dockerfile 실행
* 사용자 바이너리 실행
* 임의 패키지 설치

Agentown은 외부 AI와 외부 API를 연결하고 실행 순서를 관리하는 오케스트레이션 플랫폼이다.

3. 사용자 경험

사용자는 다음 흐름으로 하네스를 만든다.

1. 미니홈에 접속한다.
2. 에이전트 추가 버튼을 누른다.
3. 캐릭터 외형을 선택한다.
4. 이름을 입력한다.
5. 역할을 입력한다.
6. 해야 할 일을 입력한다.
7. 원하는 결과물을 입력한다.
8. 가이드와 금지사항을 입력한다.
9. 사용할 LLM 제공자와 모델을 선택한다.
10. 플랫폼이 에이전트 정의 파일을 자동 생성한다.
11. 다른 에이전트를 추가한다.
12. 연결하기 버튼을 누른다.
13. 플랫폼이 실행 순서와 전달 규칙을 제안한다.
14. 사용자가 제안을 승인하거나 수정한다.
15. 실행 버튼을 누른다.
16. 캐릭터가 실행 상태에 따라 움직이고 말풍선을 표시한다.
17. 결과물을 화면에서 확인하거나 다운로드한다.
18. 완성된 하네스를 공개·복제·내려받기 할 수 있다.

사용자는 CLI, Markdown, JSON, Git, 서버, 포트, 스레드, Worker 개념을 몰라도 사용할 수 있어야 한다.

3.1 질문형 AI 회사 설계

처음부터 에이전트 이름, 스크립트와 가이드를 직접 작성하도록 요구하지 않는다.
플랫폼은 사용자가 이해할 수 있는 다음 질문을 단계적으로 제공한다.

* 회사가 해결해야 하는 문제는 무엇인가?
* 누가 어떤 입력을 제공하는가?
* 최종 결과물은 무엇인가?
* 반드시 확인해야 하는 근거와 품질 기준은 무엇인가?
* 절대 수행하거나 출력하면 안 되는 것은 무엇인가?
* 언제 사람의 승인이 필요한가?
* 실패하면 중단할지, 수정 요청할지, 제한된 횟수로 재시도할지?

설계 LLM은 답변을 바탕으로 최대 5명의 에이전트, 공통 가이드, 스키마, 순차 실행과
단순 반려 재시도를 포함한 초안을 구조화 JSON으로 반환한다. 생성 결과를 바로
실행하거나 저장하지 말고 서버 검증기를 통과시킨 뒤 사용자에게 미리보기로 제공한다.
사용자가 승인한 경우에만 자신의 에이전트와 하네스로 저장한다.

4. 자동 에이전트 파일 생성

사용자가 에이전트 정보를 입력하면 플랫폼이 자동으로 다음 논리 파일을 생성하라.

agent-package/
├── agent.md
├── guide.md
├── input-schema.json
├── output-schema.json
└── agent.json

사용자가 직접 파일을 작성하지 않게 하라.

agent.md에는 다음을 포함하라.

* 이름
* 역할
* 책임
* 작업 순서
* 입력
* 출력
* 완료 조건
* 실패 조건
* 사용 도구
* 다른 에이전트에게 전달할 정보

guide.md에는 다음을 포함하라.

* 작업 가이드
* 품질 기준
* 금지사항
* 사실 검증 기준
* 출력 스타일
* 재작성 기준
* 승인 기준

사용자 입력이 부족하면 상위 설계 LLM이 일반적인 기준으로 초안을 보완하되, 사용자가 수정할 수 있게 하라.

5. 하네스 자동 연결

사용자가 여러 에이전트를 선택하고 연결하기를 누르면 상위 설계 LLM이 에이전트 정의를 분석해 다음을 생성하라.

harness-package/
├── harness.md
├── harness.json
├── README.md
├── agents/
├── guides/
└── schemas/

harness.md에는 사람이 읽을 수 있는 다음 정보를 포함하라.

* 하네스 목표
* 전체 업무 흐름
* 에이전트별 역할
* 단계별 입력과 출력
* 전달 규칙
* 완료 조건
* 실패 조건
* 재시도 규칙
* 사용자 승인 지점

harness.json에는 기계 실행 가능한 구조를 포함하라.

예시:

{
  "name": "blog-writing-team",
  "version": "1.0.0",
  "steps": [
    {
      "id": "writer",
      "type": "LLM",
      "agentId": "writer-agent",
      "next": "reviewer"
    },
    {
      "id": "reviewer",
      "type": "LLM",
      "agentId": "reviewer-agent",
      "onApprove": "finish",
      "onReject": "writer",
      "maxRetries": 2
    }
  ]
}

상위 LLM이 생성한 연결 구조를 그대로 실행하지 마라.

반드시 검증기를 구현하라.

검증 항목:

* 모든 Agent ID가 존재하는가
* 순환 구조가 허용 범위를 벗어나지 않는가
* 시작 단계가 존재하는가
* 종료 조건이 존재하는가
* 필수 입력이 이전 단계에서 제공되는가
* 출력 스키마와 다음 입력 스키마가 호환되는가
* 최대 단계 수를 초과하지 않는가
* 재시도 횟수가 제한되어 있는가
* 자격증명이 없는 외부 서비스가 포함되어 있지 않은가
* 위험한 작업이 포함되지 않는가

MVP에서는 최대 5개 에이전트, 순차 실행, 단순 반려 재시도만 지원하라.

병렬 실행과 자유로운 반복 루프는 제외한다.

6. 실행 아키텍처

Agentown의 핵심 실행 방식은 Orchestrator-based Workflow Architecture다.

사용자
→ Next.js Web
→ Kotlin Spring Boot
→ Harness Orchestrator
→ Step Executor
→ 외부 AI/API
→ 결과

Orchestrator는 다음을 담당한다.

* 실행 생성
* 현재 단계 판단
* 다음 단계 선택
* 입력 매핑
* 외부 API 호출 요청
* 결과 저장
* 출력 검증
* 재시도
* 타임아웃
* 사용자 승인 대기
* 전체 완료 판단

초기에는 별도 Worker 서버를 만들지 마라.

하나의 Spring Boot 애플리케이션 안에서 다음 Executor를 논리적으로 분리하라.

LlmStepExecutor
ExternalApiStepExecutor
DownloadStepExecutor
ApprovalStepExecutor

MVP에서는 Artifact Worker, Video Worker, Sandbox Worker를 별도 서버로 만들지 마라.

외부 서비스가 영상, 이미지, 음성, 자막, 파일을 생성하도록 하라.

Agentown 서버가 직접 FFmpeg, Whisper, Stable Diffusion, 영상 인코딩, GPU 추론을 실행하지 않게 하라.

7. 비동기 처리와 동시성

하나의 Spring Boot 프로세스가 여러 하네스 실행을 동시에 관리할 수 있어야 한다.

Kotlin Coroutine 또는 Java 21 Virtual Thread 중 하나를 선택하라.

Kotlin 기반이므로 기본 추천은 Kotlin Coroutine이다.

각 실행은 외부 API 응답을 기다리는 I/O 중심 작업이다.

하네스 실행 100개가 들어와도 프로세스 100개나 서버 100대가 필요하지 않다.

다음 제한을 구현하라.

전체 동시 하네스 실행: 20
사용자당 동시 실행: 1
사용자당 대기 실행: 최대 3
LLM 외부 호출 동시성: 50
일반 외부 API 동시성: 100
다운로드 프록시 동시성: 10

작업 종류별로 Semaphore를 사용하라.

한도를 초과하면 요청을 버리지 말고 DB 기반 Queue에 저장하라.

실행 상태:

QUEUED
RUNNING
WAITING_APPROVAL
SUCCEEDED
FAILED
CANCELLED
TIMEOUT

초기 Queue는 PostgreSQL 테이블로 구현하라.

Kafka와 SQS는 사용하지 마라.

서버가 재시작돼도 QUEUED 및 오래된 RUNNING 실행을 복구할 수 있어야 한다.

8. AI 계정 연결 정책

공개 서비스의 기본 실행은 사용자의 ChatGPT Plus/Pro 또는 Claude Pro/Max 구독을 사용하는 Local Runner 방식으로 처리한다. 사용자는 Agentown 웹에서 하네스를 설계하고, 사용자 컴퓨터의 Codex CLI 또는 Claude Code가 HTTPS Pull 방식으로 실행 작업을 가져간다. 공급자 로그인 토큰과 비밀번호는 Agentown 서버에 저장하지 않는다.

사용자의 컴퓨터가 꺼져 있거나 서버에서 바로 실행해야 하는 경우에는 BYOK API 키를 보조 경로로 제공한다. 플랫폼 공용 API 키를 기본 실행 경로로 사용하지 않는다.

플랫폼 공용 키를 기본 실행 경로로 사용하지 마라.

지원 Provider 예시:

OPENAI
ANTHROPIC
GOOGLE
IMAGE_PROVIDER
VIDEO_PROVIDER
TTS_PROVIDER
SEARCH_PROVIDER

각 사용자는 자신의 API 키를 등록한다.

각 에이전트는 사용할 Provider와 Model을 개별 설정할 수 있다.

하나의 하네스 안에서 에이전트마다 서로 다른 Provider를 사용할 수 있어야 한다.

예:

Writer → OpenAI
Reviewer → Anthropic
Image Agent → 외부 이미지 API
Video Agent → 외부 영상 API

API 키는 다음 정책을 지켜라.

* 평문 저장 금지
* AES-256-GCM 기반 암호화
* 운영 환경에서는 AWS KMS로 교체 가능한 인터페이스
* API 응답에 원문 반환 금지
* 마지막 몇 자리만 마스킹
* 로그 출력 금지
* 하네스 공유·복제·내려받기에 포함 금지
* 실행 이력에 포함 금지
* 관리자도 원문 조회 불가
* 복제된 하네스는 credentialId를 비운 상태로 생성

Credential 도메인:

LlmCredential
- id
- ownerId
- provider
- encryptedSecret
- maskedSecret
- status
- keyVersion
- lastVerifiedAt
- createdAt
- updatedAt

9. 실행 결과물 정책

하네스 정의와 실행 결과물은 완전히 분리하라.

하네스:

공유·복제·판매 가능한 업무 설계도

실행 결과물:

실행한 사용자 개인 소유

다른 사용자와 하네스 제작자는 실행 결과물을 볼 수 없어야 한다.

결과물 예시:

* 글
* JSON
* 이미지
* 영상
* 음성
* 자막
* PPT
* PDF
* DOCX
* ZIP
* 외부 게시 결과

Agentown은 기본적으로 결과물을 영구 보관하지 않는다.

결과 전달 우선순위:

1. 외부 AI 서비스가 제공한 다운로드 URL을 사용자에게 전달
2. 사용자 브라우저가 외부 서비스에서 HTTPS로 직접 다운로드
3. 사용자가 연결한 Google Drive, GitHub, WordPress 등으로 전송
4. 직접 URL 전달이 불가능한 경우에만 Agentown이 HTTPS 스트리밍 프록시
5. 플랫폼 임시 저장은 후속 기능으로 제한적으로 제공

SSH, SCP, SFTP를 사용자 다운로드 방식으로 사용하지 마라.

서버 간 파일 전송과 사용자 다운로드는 HTTP/HTTPS 기반으로 처리하라.

외부 파일을 Agentown 서버가 프록시할 경우 전체 파일을 메모리에 적재하지 말고 스트리밍하라.

DB에는 결과 파일 본체가 아니라 메타데이터만 저장하라.

Artifact
- id
- executionId
- ownerUserId
- type
- fileName
- mimeType
- externalUrl
- expiresAt
- status
- createdAt

10. 미니홈 UI

미니홈은 싸이월드의 개인 공간 개념을 참고하되 기존 UI, 로고, 자산을 복제하지 마라.

미니홈 기능:

* 개인 제목
* 소개
* 배경
* 바닥
* 에이전트 캐릭터
* 캐릭터 좌표
* 캐릭터 상태
* 공개 범위
* 방문자
* 일촌
* 공개 하네스
* 최근 실행
* 꾸미기 설정

캐릭터 위치는 0에서 1 사이의 정규화 좌표로 저장하라.

실행 화면에서 캐릭터는 실제 실행 이벤트에 따라 상태가 변경되어야 한다.

이벤트 예시:

EXECUTION_QUEUED
EXECUTION_STARTED
STEP_STARTED
MODEL_REQUEST_SENT
TOOL_CALLED
STEP_OUTPUT_CREATED
STEP_COMPLETED
STEP_FAILED
WAITING_APPROVAL
EXECUTION_COMPLETED
EXECUTION_FAILED

애니메이션 매핑 예시:

STEP_STARTED
→ 캐릭터 작업 시작
MODEL_REQUEST_SENT
→ 생각 또는 타이핑
TOOL_CALLED
→ 검색·파일·영상 아이콘
STEP_OUTPUT_CREATED
→ 문서 또는 결과물 생성
STEP_COMPLETED
→ 다음 캐릭터에게 결과 전달
WAITING_APPROVAL
→ 사용자 호출
STEP_FAILED
→ 오류 표시

애니메이션은 실제 실행 상태와 일치해야 한다.

단순히 일하는 척하는 가짜 애니메이션을 만들지 마라.

MVP에서는 HTML, CSS, SVG, PNG, CSS Animation을 사용하라.

PixiJS와 Phaser는 초기에는 사용하지 마라.

11. 소셜 기능

다음을 구현하라.

* 회원가입
* 로그인
* 이메일 중복 확인
* 이메일 6자리 난수 인증
* 이메일 기반 비밀번호 찾기
* 프로필
* 미니홈
* 미니홈 방문
* 일촌 신청
* 일촌 승인
* 일촌 거절
* 일촌 해제
* 사용자 차단
* 미니홈 공개 범위
* 에이전트 공개 범위
* 하네스 공개 범위
* 공개 하네스 복제
* 하네스 내려받기
* 좋아요
* 복제 수
* 최신순 목록

공개 범위:

PRIVATE
FRIENDS
PUBLIC
MARKET

12. 마켓플레이스

MVP에서는 유료 결제와 정산을 구현하지 마라.

다음만 구현하라.

* 공식 하네스
* 사용자 공개 하네스
* 무료 게시
* 무료 복제
* 내려받기
* 좋아요
* 복제 수
* 카테고리
* 검색
* 최신순
* 인기순
* 버전
* 제작자 정보

공식 초기 하네스 예시:

* 블로그 글쓰기
* PPT 구성
* 이메일 작성
* 회의록 정리
* 문서 요약
* 코드 리뷰
* 여행 일정 계획

초기에는 운영자가 직접 공식 하네스를 등록할 수 있어야 한다.

향후 확장 항목으로만 문서화하라.

* 유료 하네스 판매
* 판매 수수료
* 판매자 정산
* 미니홈 테마
* 캐릭터 스킨
* 가구 아이템
* 기업용 브랜드 공간

결제와 정산이 들어갈 때만 Saga 보상 패턴을 고려한다.

MVP 하네스 실행에는 Saga를 사용하지 마라.

13. 하네스 복제와 내려받기

하네스를 복제할 때 원본을 직접 참조하지 마라.

게시된 버전의 스냅샷으로 구매자 또는 복제자 소유의 새 하네스를 생성하라.

복제 시 포함:

* Agent 정의
* Guide
* 입력·출력 Schema
* 실행 순서
* 연결 규칙
* 추천 Provider
* 추천 Model
* 공개 가능한 설정

복제 시 제외:

* API 키
* credentialId
* 사용자 입력
* 실행 결과
* 외부 계정 정보
* 비밀 환경변수
* 인증 토큰

하네스 내려받기 형식:

harness-name/
├── README.md
├── AGENTS.md
├── CLAUDE.md
├── harness.md
├── harness.json
├── agents/
│   ├── agent-1/
│   │   ├── agent.md
│   │   ├── guide.md
│   │   ├── input-schema.json
│   │   └── output-schema.json
│   └── agent-2/
└── metadata.json

14. 기술 스택

Frontend

* Next.js
* React
* TypeScript
* Tailwind CSS
* TanStack Query
* Zustand
* React Hook Form
* Zod

Backend

* Kotlin
* Spring Boot
* Spring MVC 또는 Spring WebFlux 중 하나
* Kotlin Coroutine
* Spring Security
* Spring Data JPA
* Spring Modulith
* Spring AI
* Bean Validation
* Flyway
* PostgreSQL
* Gradle Kotlin DSL
* JUnit 5
* Testcontainers

외부 API 호출은 비동기 HTTP Client를 사용하라.

도메인 계층에 Spring AI 또는 특정 Provider SDK 타입을 직접 노출하지 마라.

Infrastructure

* Docker Compose
* GitHub Actions
* 단일 AWS Lightsail 또는 EC2
* 초기 사양: 2 vCPU / 4GB RAM
* PostgreSQL
* Cloudflare DNS/HTTPS

초기에는 다음을 사용하지 마라.

* RDS 필수화
* Redis 필수화
* SQS
* Kafka
* Kubernetes
* Load Balancer
* Auto Scaling
* GPU
* 사용자별 VM

15. 백엔드 모듈 구조

백엔드는 모듈러 모놀리스로 구현하라.

backend/
├── identity
├── profile
├── social
├── minihome
├── agent
├── harness
├── designer
├── execution
├── credential
├── marketplace
├── artifact
└── common

각 모듈 내부:

domain
application
infrastructure
presentation

규칙:

* Controller에서 Repository 직접 호출 금지
* 다른 모듈 Repository 직접 호출 금지
* Domain에서 외부 SDK 참조 금지
* 순환 의존성 금지
* 공개 Application Service 또는 Query Interface 사용
* 필요한 경우 Application Event 사용
* Spring Modulith 테스트로 모듈 경계 검증

16. 핵심 도메인

다음을 구현하라.

User
Profile
Friendship
MiniHome
RoomItem
Agent
AgentDefinition
AgentGuide
AgentModelConfig
Harness
HarnessVersion
HarnessStep
HarnessEdge
Execution
ExecutionStep
ExecutionEvent
LlmCredential
MarketProduct
ProductClone
ProductLike
Artifact

ExecutionStep 유형:

LLM
EXTERNAL_API
DOWNLOAD
APPROVAL

MVP에서는 내부 파일 렌더링 Step을 필수 구현하지 마라.

17. 핵심 API

POST   /api/auth/signup
POST   /api/auth/login
POST   /api/auth/logout
GET    /api/users/me
GET    /api/mini-homes/{handle}
PATCH  /api/mini-homes/me
PUT    /api/mini-homes/me/items
POST   /api/friendships/requests
POST   /api/friendships/{id}/accept
POST   /api/friendships/{id}/reject
DELETE /api/friendships/{id}
GET    /api/friendships
POST   /api/agents
GET    /api/agents/{id}
PATCH  /api/agents/{id}
DELETE /api/agents/{id}
POST   /api/agents/{id}/generate-definition
POST   /api/agents/{id}/test
POST   /api/harnesses
GET    /api/harnesses/{id}
PATCH  /api/harnesses/{id}
DELETE /api/harnesses/{id}
POST   /api/harnesses/{id}/connect
POST   /api/harnesses/{id}/validate
POST   /api/harnesses/{id}/publish
POST   /api/harnesses/{id}/clone
GET    /api/harnesses/{id}/download
POST   /api/harnesses/{id}/executions
GET    /api/executions/{id}
POST   /api/executions/{id}/cancel
GET    /api/executions/{id}/events
POST   /api/llm-credentials/verify
POST   /api/llm-credentials
GET    /api/llm-credentials
DELETE /api/llm-credentials/{id}
GET    /api/market/products
GET    /api/market/products/{id}
POST   /api/market/products
POST   /api/market/products/{id}/clone
POST   /api/market/products/{id}/likes
DELETE /api/market/products/{id}/likes
GET    /api/artifacts/{id}
GET    /api/artifacts/{id}/download

실행 이벤트는 SSE로 제공하라.

18. 화면

다음 화면을 구현하라.

/
 /login
 /signup
 /home
 /home/edit
 /users/[handle]
 /agents/new
 /agents/[id]/edit
 /harnesses
 /harnesses/new
 /harnesses/[id]/edit
 /executions/[id]
 /friends
 /market
 /market/[id]
 /settings/credentials

핵심 화면:

에이전트 생성 화면

* 캐릭터 선택
* 이름
* 역할
* 해야 할 일
* 입력
* 원하는 결과
* 가이드
* 금지사항
* Provider
* Model
* 자동 정의 생성
* 생성된 MD 미리보기
* 수정

하네스 연결 화면

* 에이전트 목록
* 연결 대상 선택
* 연결하기
* 추천 실행 순서
* 입력·출력 전달 관계
* 재시도 규칙
* 사용자 승인
* 검증 결과
* 실행 테스트

미니홈

* 방
* 캐릭터
* 배경
* 캐릭터 배치
* 에이전트 상태
* 공개 하네스
* 최근 실행
* 방문자
* 일촌

실행 화면

* 캐릭터 애니메이션
* 현재 단계
* 전체 단계
* 에이전트 말풍선
* 진행 상태
* 상세 로그
* 모델
* 실행 시간
* 오류
* 결과
* 다운로드

19. 보안

다음을 구현하라.

* BCrypt
* HttpOnly 인증 쿠키
* HTTPS 운영 전제
* CSRF 정책
* CORS 설정
* DTO Validation
* 소유권 검사
* 공개 범위 검사
* XSS 방어
* API Key 암호화
* 로그 마스킹
* 외부 URL 검증
* SSRF 방어
* 다운로드 권한 검사
* 만료 URL 처리
* 사용자별 Rate Limit 확장 지점
* 실행 동시성 제한
* Idempotency-Key
* 민감 값 하네스 스냅샷 제외

20. 테스트

다음 테스트를 작성하라.

사용자 및 소셜

* 이메일 인증 회원가입
* 이메일 로그인
* 이메일 중복 가입 차단
* 인증번호 원문 미저장
* 본인에게 일촌 신청 실패
* 중복 일촌 신청 실패
* 비공개 미니홈 접근 실패
* 일촌 공개 미니홈 접근 성공

에이전트

* 에이전트 생성
* 다른 사용자 에이전트 수정 실패
* Agent MD 자동 생성
* Guide 자동 생성
* 입력·출력 Schema 생성

하네스

* 하네스 연결 자동 생성
* 존재하지 않는 Agent 연결 실패
* 잘못된 Edge 검증 실패
* 최대 에이전트 수 초과 실패
* 게시 시 불변 스냅샷 생성
* 하네스 복제
* 복제본 Credential 제외
* ZIP 내려받기

실행

* 실행 Queue 등록
* 동시성 제한
* 사용자당 실행 제한
* LLM Step 성공
* 외부 API Step 성공
* 재시도
* 타임아웃
* 취소
* 재시작 후 Queue 복구
* SSE 이벤트 순서

BYOK

* API 키 평문 저장 금지
* 조회 API 원문 반환 금지
* 다른 사용자 Credential 연결 실패
* 복제본 Credential 제거
* Credential 없음 실행 차단
* Provider 불일치 실행 차단
* 로그에 API 키 미포함

결과물

* 결과물 소유자만 조회
* 외부 다운로드 URL 전달
* 만료 URL 처리
* 프록시 다운로드 스트리밍
* 다른 사용자 결과물 접근 실패

아키텍처

* Spring Modulith 모듈 경계 검증
* 다른 모듈 Repository 직접 참조 금지

21. 문서

다음 문서를 생성하라.

README.md
docs/PRODUCT_OVERVIEW.md
docs/ARCHITECTURE.md
docs/DOMAIN_MODEL.md
docs/HARNESS_SPEC.md
docs/EXECUTION_ENGINE.md
docs/BYOK_SECURITY.md
docs/RESULT_DELIVERY.md
docs/API.md
docs/MVP_SCOPE.md
docs/DECISIONS.md
docs/IMPLEMENTATION_PLAN.md

docs/DECISIONS.md에는 다음 결정과 근거를 작성하라.

* 선언형 하네스 플랫폼
* 사용자 코드 실행 제외
* 모듈러 모놀리스
* Kotlin Coroutine
* PostgreSQL Queue
* Orchestrator 기반 실행
* BYOK
* 외부 AI/API 연산
* 결과 영구 저장 제외
* HTTPS 직접 다운로드
* SSE 실행 시각화
* 초기 마켓 무료
* Saga 제외
* GPU 서버 제외
* Worker 물리 분리 제외

22. 구현 순서

반드시 다음 순서로 진행하라.

1. 저장소 분석
2. docs/IMPLEMENTATION_PLAN.md 작성
3. 모노레포 구조 생성
4. Kotlin/Spring Boot 기반 구성
5. Next.js 기반 구성
6. PostgreSQL 및 Flyway
7. 인증
8. 미니홈
9. 일촌
10. 에이전트 CRUD
11. Agent Definition Generator
12. BYOK Credential
13. 하네스 CRUD
14. Connection Generator
15. Harness Validator
16. Orchestrator
17. PostgreSQL Queue
18. Coroutine 동시성 제한
19. SSE
20. 결과 URL 및 다운로드
21. 공개·복제·내려받기
22. 마켓
23. 테스트
24. Docker Compose
25. GitHub Actions
26. README 및 문서
27. 전체 빌드
28. 전체 테스트
29. 오류 수정
30. 최종 구현 요약

23. 완료 조건

다음을 모두 만족해야 완료다.

* Backend 컴파일 성공
* Frontend 빌드 성공
* 테스트 통과
* Docker Compose 실행 가능
* 회원가입 및 로그인 가능
* 미니홈 생성 가능
* 에이전트 생성 가능
* 사용자 입력에서 Agent MD와 Guide 자동 생성
* 에이전트 연결 가능
* Harness JSON 자동 생성
* Harness 검증 가능
* 사용자 API 키 연결 가능
* 하네스 실행 가능
* 실행 요청 Queue 처리 가능
* 동시 실행 제한 작동
* SSE로 실행 상태 표시
* 캐릭터 상태가 실제 이벤트와 연동
* 결과 URL 표시
* 사용자만 결과 접근 가능
* 하네스 공개 가능
* 하네스 복제 가능
* 하네스 ZIP 내려받기 가능
* 복제 시 Credential 제외
* 다른 사용자의 미니홈 방문 가능
* 무료 마켓 검색 가능
* README만 보고 로컬 실행 가능

24. 마지막 보고 형식

모든 구현을 마친 뒤 마지막 응답에는 다음만 보고하라.

* 구현한 기능
* 구현하지 않은 기능
* 주요 아키텍처 결정
* 실행 명령
* 테스트 결과
* 주요 파일
* 현재 제한사항
* 다음 개발 우선순위

작업 도중 TODO만 남기고 완료 처리하지 마라.

외부 API 키가 없어도 전체 실행 흐름을 테스트할 수 있도록 Stub Provider를 반드시 제공하라.
