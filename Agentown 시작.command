#!/bin/zsh
set -euo pipefail

agentown_dir=${0:A:h}
cd "$agentown_dir"

if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop을 시작합니다..."
  open -a "Docker"
  for attempt in {1..90}; do
    docker info >/dev/null 2>&1 && break
    sleep 2
  done
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop이 준비되지 않았습니다. Docker 상태를 확인한 뒤 다시 실행해 주세요."
  read "?Enter를 누르면 닫힙니다."
  exit 1
fi

if [[ ! -f .env ]] || ! grep -q '^LLM_MASTER_KEY=' .env; then
  umask 077
  local_key=$(docker inspect agentown-backend-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | sed -n 's/^LLM_MASTER_KEY=//p' | head -n 1 || true)
  [[ -n "$local_key" ]] || local_key=$(openssl rand -base64 32)
  print -r -- "LLM_MASTER_KEY=$local_key" >> .env
  echo "로컬 API 키 암호화 키를 .env에 보존했습니다. 이 파일은 Git에 포함되지 않습니다."
fi

echo "Agentown DB, 백엔드, 프론트엔드를 시작합니다..."
docker compose up -d --build --wait
echo "Agentown이 준비됐습니다: http://localhost:3000"
open "http://localhost:3000"
read "?Enter를 누르면 이 창만 닫힙니다. 서버는 계속 실행됩니다."
