#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMAND="${1:-help}"
shift || true

usage() {
  cat <<'USAGE'
Usage: ./scripts/dev.sh <command>

Commands:
  up       Generate local credentials if needed, build, and start Flow + demo Worker
  down     Stop the development stack and retain database volumes
  ps       Show service status
  logs     Follow service logs (optional service name)
  help     Show this help

The default stack is for isolated local development only. Observability services
remain optional and are not started by this command.
USAGE
}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

ensure_local_env() {
  if [[ ! -e .env ]]; then
    command -v openssl >/dev/null 2>&1 || fail 'OpenSSL is required to generate local-only credentials.'
    if (set -o noclobber; umask 077; cat > .env <<EOF_ENV
FLOW_DB_ROOT_PASSWORD=$(openssl rand -hex 32)
FLOW_DB_PASSWORD=$(openssl rand -hex 32)
FLOW_ADMIN_USERNAME=admin
FLOW_ADMIN_PASSWORD=Admin-$(openssl rand -hex 12)
OTRYX_RPC_SHA=0addd7df17c5ba4a0b77e2d30e4c7c75d7ac31f2
FLOW_NACOS_AUTH_TOKEN=$(openssl rand -base64 48 | tr -d '\n')
FLOW_NACOS_IDENTITY_VALUE=$(openssl rand -hex 24)
FLOW_GRAFANA_ADMIN_USER=admin
FLOW_GRAFANA_ADMIN_PASSWORD=Grafana-$(openssl rand -hex 12)
EOF_ENV
    ) 2>/dev/null; then
      chmod 600 .env
      printf 'Created private local credentials in %s/.env\n' "$ROOT"
    else
      [[ -e .env ]] || fail 'Could not create .env. Check directory permissions and OpenSSL.'
    fi
  fi

  local key
  for key in \
    FLOW_DB_ROOT_PASSWORD FLOW_DB_PASSWORD FLOW_ADMIN_USERNAME FLOW_ADMIN_PASSWORD \
    OTRYX_RPC_SHA FLOW_NACOS_AUTH_TOKEN FLOW_NACOS_IDENTITY_VALUE; do
    grep -Eq "^${key}=.+$" .env || fail "Missing ${key} in .env. Remove the copied template to generate local credentials, or set the required value yourself."
  done
  if grep -Eq 'REPLACE_WITH|CHANGE_ME|<[^>]+>' .env; then
    fail 'Replace credential placeholders in .env before starting the stack.'
  fi
}

cd "$ROOT"
case "$COMMAND" in
  up)
    [[ $# -eq 0 ]] || fail 'The up command does not accept extra arguments.'
    command -v docker >/dev/null 2>&1 || fail 'Docker Engine with Compose V2 is required.'
    docker compose version >/dev/null 2>&1 || fail 'Docker Compose V2 is required.'
    ensure_local_env
    docker compose config --quiet
    docker compose build
    bash scripts/start_dev_compose.sh
    docker compose --profile worker-demo up -d flow-worker-demo
    docker compose ps
    printf '\nOTRYX Flow is available at http://127.0.0.1:8088\n'
    printf 'Sign in as %s; the generated password is stored in %s/.env\n' \
      "$(sed -n 's/^FLOW_ADMIN_USERNAME=//p' .env | head -n 1)" "$ROOT"
    printf 'Stop the stack with ./scripts/dev.sh down. Database volumes are retained.\n'
    ;;
  down)
    command -v docker >/dev/null 2>&1 || fail 'Docker Engine with Compose V2 is required.'
    docker compose --profile worker-demo down --remove-orphans
    ;;
  ps)
    command -v docker >/dev/null 2>&1 || fail 'Docker Engine with Compose V2 is required.'
    docker compose --profile worker-demo ps
    ;;
  logs)
    command -v docker >/dev/null 2>&1 || fail 'Docker Engine with Compose V2 is required.'
    docker compose --profile worker-demo logs -f "$@"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    usage >&2
    fail "Unknown command: $COMMAND"
    ;;
esac
