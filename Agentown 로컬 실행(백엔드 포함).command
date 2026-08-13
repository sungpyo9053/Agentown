#!/bin/bash
set -e
cd "$(dirname "$0")"
export NONINTERACTIVE=1
export HOMEBREW_NO_ENV_HINTS=1
LOG_PREFIX="[Agentown 로컬 실행]"

echo "$LOG_PREFIX 시작합니다. 이 창을 닫지 말고 그대로 두세요."
echo ""

# --- -1. 이전 실행에서 남은 프로세스 정리 (포트 8080/3000 점유 방지) -------
for PORT in 8080 3000; do
  OLD_PIDS="$(lsof -ti tcp:$PORT 2>/dev/null || true)"
  if [ -n "$OLD_PIDS" ]; then
    echo "$LOG_PREFIX 이전에 남은 프로세스 정리 중 (포트 $PORT): $OLD_PIDS"
    kill -9 $OLD_PIDS 2>/dev/null || true
  fi
done
sleep 1

# --- 0. Homebrew 확인 ---------------------------------------------------
if ! command -v brew >/dev/null 2>&1; then
  if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"; fi
  if [ -x /usr/local/bin/brew ]; then eval "$(/usr/local/bin/brew shellenv)"; fi
fi
if ! command -v brew >/dev/null 2>&1; then
  echo "$LOG_PREFIX Homebrew를 찾을 수 없습니다. https://brew.sh 안내대로 먼저 설치한 뒤 이 스크립트를 다시 실행해주세요."
  read -p "엔터를 누르면 창을 닫습니다..."
  exit 1
fi

# --- 1. PostgreSQL 16 설치 & 기동 ---------------------------------------
if ! brew list postgresql@16 >/dev/null 2>&1; then
  echo "$LOG_PREFIX PostgreSQL 16 설치 중... (몇 분 걸릴 수 있어요)"
  yes | brew install postgresql@16
fi
export PATH="/opt/homebrew/opt/postgresql@16/bin:/usr/local/opt/postgresql@16/bin:$PATH"
brew services start postgresql@16 >/dev/null 2>&1 || true

echo "$LOG_PREFIX PostgreSQL 기동 대기 중..."
for i in $(seq 1 30); do
  pg_isready -h localhost -p 5432 >/dev/null 2>&1 && break
  sleep 1
done

# --- 2. DB 롤/데이터베이스 생성 (이미 있으면 건너뜀) ----------------------
SYSTEM_USER="$(whoami)"
psql -h localhost -p 5432 -U "$SYSTEM_USER" -d postgres -tAc \
  "SELECT 1 FROM pg_roles WHERE rolname='agent_village'" | grep -q 1 || \
  psql -h localhost -p 5432 -U "$SYSTEM_USER" -d postgres -c \
  "CREATE ROLE agent_village LOGIN PASSWORD 'agent_village_local';"
psql -h localhost -p 5432 -U "$SYSTEM_USER" -d postgres -tAc \
  "SELECT 1 FROM pg_database WHERE datname='agent_village'" | grep -q 1 || \
  psql -h localhost -p 5432 -U "$SYSTEM_USER" -d postgres -c \
  "CREATE DATABASE agent_village OWNER agent_village;"

# --- 3. LLM_MASTER_KEY 생성(최초 1회) 및 환경변수 -------------------------
KEY_FILE=".agentown_local_key"
if [ ! -f "$KEY_FILE" ]; then
  openssl rand -base64 32 > "$KEY_FILE"
fi
export LLM_MASTER_KEY="$(cat "$KEY_FILE")"
export LLM_KEY_VERSION=v1
export DB_URL="jdbc:postgresql://localhost:5432/agent_village"
export DB_USERNAME=agent_village
export DB_PASSWORD=agent_village_local
export SMS_EXPOSE_DEVELOPMENT_VALUES=true
export CORS_ALLOWED_ORIGINS="http://localhost:3000,http://127.0.0.1:3000"

# --- 4. 백엔드(Spring Boot) 백그라운드 실행 -------------------------------
# 이 프로젝트는 Java 17을 요구합니다 (backend/build.gradle.kts 참고).
if ! brew list openjdk@17 >/dev/null 2>&1; then
  echo "$LOG_PREFIX Java 17 설치 중..."
  yes | brew install openjdk@17
fi
export JAVA_HOME="$(brew --prefix openjdk@17)"
export PATH="$JAVA_HOME/bin:$PATH"

if ! command -v gradle >/dev/null 2>&1; then
  echo "$LOG_PREFIX Gradle 설치 중..."
  yes | brew install gradle
fi

echo "$LOG_PREFIX 백엔드 빌드/기동 중... (최초 실행은 5분 이상 걸릴 수 있어요)"
(gradle :backend:bootRun --no-daemon > backend.log 2>&1 &)

for i in $(seq 1 150); do
  curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 && { echo "$LOG_PREFIX 백엔드 기동 완료"; break; }
  sleep 2
done

# --- 5. 프론트엔드 실행 --------------------------------------------------
echo "$LOG_PREFIX 프론트엔드 설치/기동 중..."
(cd frontend && npm install && npm run dev > ../frontend.log 2>&1 &)

for i in $(seq 1 60); do
  curl -sf http://localhost:3000 >/dev/null 2>&1 && { echo "$LOG_PREFIX 프론트엔드 기동 완료"; break; }
  sleep 2
done

open "http://localhost:3000"

echo ""
echo "$LOG_PREFIX 모두 켜졌습니다: http://localhost:3000"
echo "$LOG_PREFIX 로그: backend.log / frontend.log (repo 폴더에 생성됨)"
echo "$LOG_PREFIX 끄려면 이 터미널 창을 닫거나 Ctrl+C 를 누르세요."
wait
