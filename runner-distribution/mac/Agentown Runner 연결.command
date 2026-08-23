#!/bin/zsh
set -euo pipefail

runner_dir=${0:A:h}
cd "$runner_dir"
# Finder에서 이 파일을 control-클릭 > 열기로 한 번 승인한 경우,
# 같은 배포 폴더의 시작/종료 파일에 남은 다운로드 격리 표시를 함께 정리합니다.
xattr -dr com.apple.quarantine "$runner_dir" 2>/dev/null || true
chmod +x "Agentown Runner 시작.command" "Agentown Runner 종료.command"

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js가 필요합니다. 열린 설치 페이지에서 LTS 버전을 설치한 뒤 다시 실행하세요."
  open "https://nodejs.org/en/download"
  read "?Enter를 누르면 닫힙니다."
  exit 1
fi

echo "Agentown AI 연결 화면에서 만든 일회용 Runner 토큰을 붙여 넣으세요."
read -s "runner_token?Runner 토큰: "
echo
[[ ${#runner_token} -ge 32 ]] || { echo "토큰이 올바르지 않습니다."; exit 1; }

echo "1) ChatGPT Plus/Pro · Codex  2) Claude Pro/Max · Claude Code"
read "choice?선택 [1]: "
provider=$([[ "${choice:-1}" == "2" ]] && echo CLAUDE || echo CODEX)

if [[ "$provider" == "CODEX" ]]; then
  if ! command -v codex >/dev/null 2>&1; then
    echo "Codex CLI가 필요합니다. 설치 안내 페이지를 엽니다."
    open "https://developers.openai.com/codex/cli/"
    read "?설치 후 이 파일을 다시 실행하세요. Enter를 누르면 닫힙니다."
    exit 1
  fi
  codex login status || codex login
else
  if ! command -v claude >/dev/null 2>&1; then
    echo "Claude Code가 필요합니다. 설치 안내 페이지를 엽니다."
    open "https://docs.anthropic.com/en/docs/claude-code/setup"
    read "?설치 후 이 파일을 다시 실행하세요. Enter를 누르면 닫힙니다."
    exit 1
  fi
  claude auth status || claude auth login
fi

mkdir -p .agentown
chmod 700 .agentown
umask 077
printf 'AGENTOWN_SERVER_URL=%q\nAGENTOWN_RUNNER_PROVIDER=%q\nAGENTOWN_RUNNER_TOKEN=%q\n' \
  "https://agentown.reviewdr.kr" "$provider" "$runner_token" > .agentown/runner.env

echo "연결 설정을 이 폴더 안에 안전하게 저장했습니다."
echo "이제 'Agentown Runner 시작.command'를 더블클릭하세요."
read "?Enter를 누르면 닫힙니다."
