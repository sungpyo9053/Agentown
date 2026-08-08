#!/bin/sh
set -eu

project_dir=${AGENTOWN_PROJECT_DIR:-/opt/agentown}
backup_dir=${AGENTOWN_BACKUP_DIR:-/opt/agentown-backups}

case "$project_dir" in /opt/agentown|/opt/agentown/*) ;; *) echo "Unexpected project directory" >&2; exit 1 ;; esac
case "$backup_dir" in /opt/agentown-backups|/opt/agentown-backups/*) ;; *) echo "Unexpected backup directory" >&2; exit 1 ;; esac

mkdir -p "$backup_dir"
umask 077
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$backup_dir/agentown-$stamp.sql.gz"

cd "$project_dir"
docker compose --env-file deploy/.env.production -f docker-compose.production.yml exec -T postgres \
  pg_dump -U "${POSTGRES_USER:-agent_village}" "${POSTGRES_DB:-agent_village}" | gzip -9 > "$target"

test -s "$target"
echo "$target"
