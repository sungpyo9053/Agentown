#!/usr/bin/env bash
set -euo pipefail

project=${1:?project required}
case "$project" in
  agentown|agentown-staging) ;;
  *) echo "invalid project" >&2; exit 2 ;;
esac

observed=$(docker exec "$project-backend-1" wget -q -O - http://localhost:8080/api/version)
health=$(docker exec "$project-backend-1" wget -q -O - http://localhost:8080/actuator/health)
docker exec "$project-frontend-1" node -e "fetch('http://127.0.0.1:3000/login').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
database=$(docker inspect "$project-postgres-1" --format '{{.State.Health.Status}}')
[[ "$database" == healthy ]]
printf '%s\n%s\n' "$observed" "$health"
