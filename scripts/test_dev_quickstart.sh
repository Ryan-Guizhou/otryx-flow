#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$DOCKER_CALLS"
if [[ "$*" == "compose ps -aq nacos-bootstrap" ]]; then
  printf 'bootstrap-container\n'
elif [[ "$1" == inspect ]]; then
  printf 'exited:0\n'
elif [[ "$1" == compose && "$2" == logs ]]; then
  service="${*: -1}"
  case "$service" in
    flow-metadata) app=FlowMetadataApplication ;;
    flow-scheduler|flow-scheduler-replica) app=FlowSchedulerApplication ;;
    flow-dispatcher|flow-dispatcher-replica) app=FlowDispatcherApplication ;;
    flow-engine) app=FlowEngineApplication ;;
    flow-api) app=FlowApiApplication ;;
    *) app=UnknownApplication ;;
  esac
  printf 'Started %s in 1.0 seconds\n' "$app"
fi
DOCKER
chmod +x "$TMP/bin/docker"

run_up() {
  local project_dir="$1"
  mkdir -p "$project_dir/scripts"
  cp "$ROOT/compose.yaml" "$project_dir/compose.yaml"
  cp "$ROOT/scripts/dev.sh" "$ROOT/scripts/start_dev_compose.sh" "$project_dir/scripts/"
  DOCKER_CALLS="$TMP/docker-calls" PATH="$TMP/bin:$PATH" \
    bash "$project_dir/scripts/dev.sh" up
}

run_up "$TMP/generated" > "$TMP/generated-output"
test -f "$TMP/generated/.env"
FILE_MODE="$(stat -c '%a' "$TMP/generated/.env" 2>/dev/null || stat -f '%Lp' "$TMP/generated/.env")"
test "$FILE_MODE" = 600
! grep -q 'REPLACE_WITH' "$TMP/generated/.env"
grep -q '^OTRYX_RPC_SHA=0addd7df17c5ba4a0b77e2d30e4c7c75d7ac31f2$' "$TMP/generated/.env"
grep -q 'compose build' "$TMP/docker-calls"
grep -q 'compose --profile worker-demo up -d flow-worker-demo' "$TMP/docker-calls"
! grep -Eq 'prometheus|grafana|otel-collector' "$TMP/docker-calls"

cp -R "$TMP/generated" "$TMP/existing"
sed -i 's/^FLOW_ADMIN_PASSWORD=.*/FLOW_ADMIN_PASSWORD=existing-secret/' "$TMP/existing/.env"
DOCKER_CALLS="$TMP/existing-calls" PATH="$TMP/bin:$PATH" \
  bash "$TMP/existing/scripts/dev.sh" up > "$TMP/existing-output"
grep -q '^FLOW_ADMIN_PASSWORD=existing-secret$' "$TMP/existing/.env"

cp -R "$TMP/generated" "$TMP/invalid"
sed -i 's/^FLOW_DB_PASSWORD=.*/FLOW_DB_PASSWORD=REPLACE_WITH_PASSWORD/' "$TMP/invalid/.env"
if DOCKER_CALLS="$TMP/invalid-calls" PATH="$TMP/bin:$PATH" \
  bash "$TMP/invalid/scripts/dev.sh" up > "$TMP/invalid-output" 2>&1; then
  echo 'Expected incomplete credentials to be rejected' >&2
  exit 1
fi
grep -q 'Replace credential placeholders' "$TMP/invalid-output"
! grep -q 'compose build' "$TMP/invalid-calls"

echo 'PASS: one-command quickstart configures a private local environment and starts the worker without observability services.'
