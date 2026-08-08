#!/bin/zsh
set -euo pipefail
agentown_dir=${0:A:h}
cd "$agentown_dir"
mkdir -p .agentown
chmod 700 .agentown
echo "Agentown AI 연결 화면에서 발급된 일회성 Runner 토큰을 붙여 넣으세요."
read -s "runner_token?Runner 토큰: "
echo
[[ ${#runner_token} -ge 32 ]] || { echo "토큰이 올바르지 않습니다."; exit 1; }
echo "1) ChatGPT Pro / Codex  2) Claude Pro / Claude Code"
read "choice?선택 [1]: "
provider=$([[ "${choice:-1}" == "2" ]] && echo CLAUDE || echo CODEX)
if [[ "$provider" == "CODEX" ]]; then codex login status || codex login; else claude auth status || claude auth login; fi
umask 077
printf 'AGENTOWN_SERVER_URL=%q\nAGENTOWN_RUNNER_PROVIDER=%q\nAGENTOWN_RUNNER_TOKEN=%q\n' "http://localhost:8080" "$provider" "$runner_token" > .agentown/runner.env
echo "연결 설정을 저장했습니다. Agentown 시작.command를 다시 실행하세요."
read "?Enter를 누르면 닫힙니다."
