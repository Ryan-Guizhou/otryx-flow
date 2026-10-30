#!/usr/bin/env bash
# Isolated Compose failure-injection: database refresh fails without killing business JVMs.
set -euo pipefail
[ "${FLOW_E2E_DATABASE_REFRESH_FAULT:-0}" = 1 ] || {
  echo 'Refusing database outage outside explicitly enabled isolated CI'; exit 1;
}
: "${FLOW_ADMIN_USERNAME:?Missing admin username}"
: "${FLOW_ADMIN_PASSWORD:?Missing admin password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
MYSQL_STOPPED=0

restore() {
  if [ "$MYSQL_STOPPED" = 1 ]; then
    docker compose up -d --no-deps mysql >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap restore EXIT

snapshot() {
  docker compose --profile observability-test run --rm --no-deps \
    --entrypoint sh observability-probe -ec \
    'curl --fail --silent --show-error --max-time 10 http://flow-scheduler:9091/actuator/prometheus' \
    > "$WORK/metrics.prom" 2>/dev/null
}

metric() {
  local metric_name="$1"
  python3 - "$WORK/metrics.prom" "$metric_name" <<'PY'
import re
import sys
from pathlib import Path

exposition = Path(sys.argv[1]).read_text(encoding="utf-8")
name = re.escape(sys.argv[2])
pattern = rf"^{name}(?:\{{[^}}]+\}})?\s+([0-9]+(?:\.[0-9]+)?)\s*$"
match = re.search(pattern, exposition, re.MULTILINE)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
}

# Prove authenticated business calls work before intentionally stopping MySQL.
COOKIE="$WORK/cookie"
curl --fail --silent --show-error --max-time 15 \
  -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/auth/csrf" > "$WORK/csrf.json"
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$WORK/csrf.json")"
python3 -c 'import json,os,sys;json.dump({"username":os.environ["FLOW_ADMIN_USERNAME"],"password":os.environ["FLOW_ADMIN_PASSWORD"]},open(sys.argv[1],"w"))' "$WORK/login.json"
code="$(curl --silent --show-error --max-time 15 -o "$WORK/login-response.json" -w '%{http_code}' \
  -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $TOKEN" --data-binary @"$WORK/login.json" "$BASE/api/v1/auth/login")"
[ "$code" = 200 ] || { echo "Database fault E2E login failed: HTTP $code"; exit 1; }
code="$(curl --silent --show-error --max-time 15 -o /dev/null -w '%{http_code}' \
  -b "$COOKIE" "$BASE/api/v1/projects")"
[ "$code" = 200 ] || { echo "Metadata RPC unavailable before database fault"; exit 1; }

healthy=0
for i in $(seq 1 20); do
  if snapshot; then
    state="$(metric flow_database_metrics_refresh_state || true)"
    if [ "$state" = 1 ]; then healthy=1; break; fi
  fi
  sleep 2
done
[ "$healthy" = 1 ] || { echo "Scheduler database metrics never reached healthy state"; exit 1; }
last_good="$(metric flow_schedule_enabled)"
[ -n "$last_good" ] || { echo "Missing last-good Scheduler metric"; exit 1; }

MYSQL_STOPPED=1
docker compose stop mysql
fault_observed=0
for i in $(seq 1 60); do
  if snapshot; then
    state="$(metric flow_database_metrics_refresh_state || true)"
    if [ "$state" = 2 ]; then fault_observed=1; break; fi
  fi
  sleep 2
done
[ "$fault_observed" = 1 ] || { echo "Scheduler did not report real database refresh failure"; exit 1; }
[ "$(metric flow_schedule_enabled)" = "$last_good" ] || {
  echo "Database failure changed last-good business gauge snapshot"; exit 1;
}
for service in flow-scheduler flow-dispatcher flow-engine; do
  container="$(docker compose ps -q "$service")"
  [ -n "$container" ] || { echo "Missing business service: $service"; exit 1; }
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ] || {
    echo "Business JVM exited because of metrics refresh: $service"; exit 1;
  }
done
echo "PASS: real MySQL outage is isolated from Scheduler metrics refresh and service liveness"

docker compose up -d --no-deps mysql
MYSQL_STOPPED=0
mysql_id="$(docker compose ps -q mysql)"
[ -n "$mysql_id" ] || { echo "Could not restore MySQL container"; exit 1; }
recovered=0
for i in $(seq 1 75); do
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' \
    "$mysql_id" || true)"
  if [ "$health" = healthy ] && snapshot; then
    state="$(metric flow_database_metrics_refresh_state || true)"
    if [ "$state" = 1 ]; then recovered=1; break; fi
  fi
  sleep 2
done
[ "$recovered" = 1 ] || { echo "Scheduler metric refresh did not recover after MySQL return"; exit 1; }

business_recovered=0
for i in $(seq 1 30); do
  code="$(curl --silent --show-error --max-time 12 -o /dev/null -w '%{http_code}' \
    -b "$COOKIE" "$BASE/api/v1/projects" || true)"
  if [ "$code" = 200 ]; then business_recovered=1; break; fi
  sleep 2
done
[ "$business_recovered" = 1 ] || { echo "Authenticated API did not recover after database restart"; exit 1; }
echo "PASS: database metrics state 2->1 and authenticated RPC recovered after real MySQL restart"
