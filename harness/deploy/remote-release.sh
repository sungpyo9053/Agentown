#!/usr/bin/env bash
set -euo pipefail

target=${1:?target required}
expected_sha=${2:?sha required}
archive=${3:?archive required}
compose_source=${4:?compose required}

[[ "$expected_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "invalid SHA" >&2; exit 2; }
inbox=/home/agentown-release/inbox
archive=$(realpath "$archive")
compose_source=$(realpath "$compose_source")
[[ "$archive" == "$inbox/"* && "$compose_source" == "$inbox/"* ]] || { echo "release inputs must come from the restricted inbox" >&2; exit 2; }
[[ -f "$archive" && -f "$compose_source" ]] || { echo "release input is missing" >&2; exit 2; }
case "$target" in
  staging) base=/opt/agentown-staging; project=agentown-staging ;;
  production|rollback) base=/opt/agentown; project=agentown ;;
  *) echo "invalid target" >&2; exit 2 ;;
esac

release_dir="$base/releases/$expected_sha"
shared_dir="$base/shared"
mkdir -p "$release_dir" "$shared_dir"
if [[ ! -f "$release_dir/.archive-extracted" ]]; then
  tar -xzf "$archive" -C "$release_dir"
  touch "$release_dir/.archive-extracted"
fi
env_file="$shared_dir/release.env"
if [[ "$target" == staging ]]; then
  install -m 0644 "$compose_source" "$release_dir/docker-compose.release.yml"
  compose_file=docker-compose.release.yml
elif [[ -f "$base/deploy/.env.production" && ! -f "$env_file" ]]; then
  install -m 0600 "$base/deploy/.env.production" "$env_file"
  compose_file=docker-compose.production.yml
else
  compose_file=docker-compose.production.yml
fi
[[ -f "$env_file" ]] || { echo "release environment is missing" >&2; exit 3; }
if grep -q '^RELEASE_COMMIT_SHA=' "$env_file"; then
  sed "s/^RELEASE_COMMIT_SHA=.*/RELEASE_COMMIT_SHA=$expected_sha/" "$env_file" > "$env_file.next"
else
  cp "$env_file" "$env_file.next"
  printf 'RELEASE_COMMIT_SHA=%s\n' "$expected_sha" >> "$env_file.next"
fi
chmod 0600 "$env_file.next"
mv "$env_file.next" "$env_file"
if [[ "$target" != staging && -d "$base/deploy/codex-auth" ]]; then
  mkdir -p "$release_dir/deploy"
  rm -rf "$release_dir/deploy/codex-auth"
  cp -a "$base/deploy/codex-auth" "$release_dir/deploy/codex-auth"
fi

cd "$release_dir"
COMPOSE_PARALLEL_LIMIT=1 docker compose -p "$project" --env-file "$env_file" -f "$compose_file" up -d --build --remove-orphans
docker compose -p "$project" --env-file "$env_file" -f "$compose_file" ps
printf '%s\n' "$expected_sha" > "$shared_dir/deployed-sha"
rm -f "$archive" "$compose_source"
