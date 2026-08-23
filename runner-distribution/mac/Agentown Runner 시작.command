#!/bin/zsh
set -euo pipefail

runner_dir=${0:A:h}
cd "$runner_dir"

if [[ ! -f .agentown/runner.env ]]; then
  echo "먼저 'Agentown Runner 연결.command'를 실행해 연결을 완료하세요."
  read "?Enter를 누르면 닫힙니다."
  exit 1
fi

if [[ -f .agentown/runner.pid ]]; then
  runner_pid=$(<.agentown/runner.pid)
  if [[ "$runner_pid" == <-> ]] && kill -0 "$runner_pid" 2>/dev/null; then
    echo "Agentown Runner가 이미 실행 중입니다."
    read "?Enter를 누르면 닫힙니다."
    exit 0
  fi
fi

set -a
source .agentown/runner.env
set +a
nohup node runner.mjs > .agentown/runner.log 2>&1 &
echo $! > .agentown/runner.pid
sleep 2

if kill -0 "$(<.agentown/runner.pid)" 2>/dev/null; then
  echo "Agentown Runner를 시작했습니다. 이 창은 닫아도 됩니다."
  open "https://agentown.reviewdr.kr/settings/credentials"
else
  echo "Runner 시작에 실패했습니다. .agentown/runner.log를 확인하세요."
fi
read "?Enter를 누르면 닫힙니다."
