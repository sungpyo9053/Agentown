#!/bin/zsh
set -euo pipefail

agentown_dir=${0:A:h}
cd "$agentown_dir"

if [[ -f .agentown/runner.pid ]]; then
  runner_pid=$(<.agentown/runner.pid)
  if [[ "$runner_pid" == <-> ]] && kill -0 "$runner_pid" 2>/dev/null; then kill "$runner_pid"; fi
  rm -f .agentown/runner.pid
fi

echo "Agentown 컨테이너를 정지합니다. 계정과 하네스 DB 볼륨은 보존됩니다."
if [[ -f .env ]] && grep -q '^LLM_MASTER_KEY=' .env; then
  docker compose stop
else
  running_key=$(docker inspect agentown-backend-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | sed -n 's/^LLM_MASTER_KEY=//p' | head -n 1 || true)
  if [[ -n "$running_key" ]]; then
    LLM_MASTER_KEY="$running_key" docker compose stop
  else
    echo "정지할 Agentown 컨테이너가 없습니다."
  fi
fi
echo "정지 완료. 다시 시작하려면 'Agentown 시작.command'를 더블클릭하세요."
read "?Enter를 누르면 닫힙니다."
