# 시작하기

이 패키지는 **두 CSV 파일을 ID 기준으로 비교해서 추가 수정 삭제 행을 표로 만들어줘** 업무를 생성된 Agent 역할과 Workflow에 따라 수행합니다.

## 대화형 실행

```bash
unzip agentown-agent.zip
cd agentown-agent
codex
# 또는
claude
```

첫 요청 예시: `examples/sample-input.json의 입력으로 이 업무를 실행해 줘.`

`examples/sample-input.json`은 입력 형식 확인용 예시입니다. 실행 전에 예시 값을 실제 업무 자료로 바꿔 주세요. 예시 실행 성공은 실제 업무 결과의 품질을 보장하지 않습니다.

Codex와 Claude Code는 `AGENTS.md`를 공통 실행 계약으로 사용합니다. 입력 예시는 아래와 같습니다.

```json
{
  "csvA" : "id,name\n1,old\n2,remove\n",
  "csvB" : "id,name\n1,new\n3,add\n",
  "keyColumns" : [ "id" ]
}
```

## 환경변수와 제한

필요한 환경변수: `없음`

외부 Connector가 연결되지 않았으면 실제 결과를 만들 수 없습니다. Mock으로 성공을 꾸미지 말고 `EXECUTION_NOT_CONFIGURED`로 중단합니다.

## 고급 자동 Runner

Python 3.11 이상과 로그인된 Codex CLI가 필요합니다. 먼저 설치 상태만 점검하세요.
`python3 runners/python/runner.py --check`는 AI를 호출하거나 도구를 설치하지 않습니다.

```bash
python3 -m venv .venv
.venv/bin/python -m pip install './runtime[artifacts]'
.venv/bin/python runners/python/runner.py --office-input
```

위 명령은 파일 제작 도구까지 전용 가상환경에 설치합니다. 시스템 Python이나 기존 프로젝트의 패키지는 변경하지 않습니다.
실제 파일은 패키지의 `results` 아래 매번 새 폴더에 생성합니다. 기존 파일은 덮어쓰지 않습니다.
파일 제작은 로컬 실행기 전용이며, 생성 성공이 내용·출처·레이아웃 검토 완료를 뜻하지는 않습니다.

## 내 PC에서 직원들이 일하는 회사 화면 보기

위 실행 환경을 준비한 뒤 `.venv/bin/python runners/python/runner.py --office-input`로 실행하세요.
회사 화면에 실제 자료를 입력하고 ‘이 자료로 직원들 실행’을 누르세요. 누르기 전에는 AI를 호출하지 않으며 예시 자료를 자동 실행하지 않습니다.
Windows PowerShell에서는 `py -3 -m venv .venv`, `.venv\Scripts\python.exe -m pip install './runtime[artifacts]'`, `.venv\Scripts\python.exe runners/python/runner.py --office-input` 순서로 실행합니다. Python 3.11 이상이 필요합니다.
기존 예시 파일을 사용하는 고급 실행은 `--office`로 유지됩니다.
브라우저의 로컬 회사 화면에서 실제 에이전트별 진행 상태와 결과를 봅니다. AI 호출에는 설정된 제공자와 인터넷이 필요합니다.
실행이 끝나면 Ctrl+C로 로컬 화면 서버를 종료합니다. 웹 실행이나 별도 채팅의 작업 상태는 표시하지 않습니다.
