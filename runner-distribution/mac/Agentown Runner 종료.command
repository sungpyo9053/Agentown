#!/bin/zsh
set -euo pipefail

runner_dir=${0:A:h}
cd "$runner_dir"

if [[ -f .agentown/runner.pid ]]; then
  runner_pid=$(<.agentown/runner.pid)
  if [[ "$runner_pid" == <-> ]] && kill -0 "$runner_pid" 2>/dev/null; then
    kill "$runner_pid"
  fi
  rm -f .agentown/runner.pid
fi

echo "Agentown Runner를 종료했습니다. 연결 정보는 유지됩니다."
read "?Enter를 누르면 닫힙니다."
