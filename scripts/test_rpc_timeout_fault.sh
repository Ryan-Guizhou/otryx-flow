#!/usr/bin/env bash
# Isolated CI only: SIGSTOP a Provider JVM to prove an actual RPC deadline counter and recovery.
set -euo pipefail
[ "${FLOW_E2E_RPC_TIMEOUT_FAULT:-0}" = 1 ] || {
  echo 'Refusing RPC timeout injection outside explicitly enabled isolated CI'; exit 1;
}
: "${FLOW_ADMIN_USERNAME:?Missing admin username}"
: "${FLOW_ADMIN_PASSWORD:?Missing admin password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
CONTAINER=""
FROZEN=0

restore() {
  if [ "$FROZEN" = 1 ] && [ -n "$CONTAINER" ]; then
    docker kill --signal=CONT "$CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap restore EXIT

snapshot() {
  docker compose --profile observability-test run --rm --no-deps \
    --entrypoint sh observability-probe -ec \
    'curl --fail --silent --show-error --max-time 10 http://flow-api:9091/actuator/prometheus'
}

COOKIE="$WORK/cookie"
curl --fail --silent --show-error --max-time 15 \
  -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/auth/csrf" > "$WORK/csrf.json"
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$WORK/csrf.json")"
python3 -c 'import json,os,sys;json.dump({"username":os.environ["FLOW_ADMIN_USERNAME"],"password":os.environ["FLOW_ADMIN_PASSWORD"]},open(sys.argv[1],"w"))' "$WORK/login.json"
code="$(curl --silent --show-error --max-time 15 -o "$WORK/login-response.json" -w '%{http_code}' \
  -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $TOKEN" --data-binary @"$WORK/login.json" "$BASE/api/v1/auth/login")"
[ "$code" = 200 ] || { echo "RPC fault probe login failed: HTTP $code"; exit 1; }
code="$(curl --silent --show-error --max-time 15 -o /dev/null -w '%{http_code}' \
  -b "$COOKIE" "$BASE/api/v1/projects")"
[ "$code" = 200 ] || { echo "Metadata RPC unavailable before fault: HTTP $code"; exit 1; }

snapshot > "$WORK/before.prom"
CONTAINER="$(docker compose ps -q flow-metadata)"
[ -n "$CONTAINER" ] || { echo "Missing live Metadata container"; exit 1; }
[ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER")" = true ] || {
  echo "Metadata container is not running"; exit 1;
}

# Pause the JVM without closing existing sockets or deregistering its Nacos membership.
# The caller must enforce a deadline and the host always resumes PID 1 in the EXIT trap.
FROZEN=1
docker kill --signal=STOP "$CONTAINER" >/dev/null
code="$(curl --silent --show-error --max-time 12 -o "$WORK/during.json" -w '%{http_code}' \
  -b "$COOKIE" "$BASE/api/v1/projects" || true)"
docker kill --signal=CONT "$CONTAINER" >/dev/null
FROZEN=0
[ "$code" != 200 ] || { echo "Paused metadata unexpectedly returned HTTP 200"; exit 1; }

restored=0
for i in $(seq 1 30); do
  code="$(curl --silent --show-error --max-time 10 -o /dev/null -w '%{http_code}' \
    -b "$COOKIE" "$BASE/api/v1/projects" || true)"
  if [ "$code" = 200 ]; then restored=1; break; fi
  sleep 2
done
[ "$restored" = 1 ] || { echo "Metadata RPC did not recover after SIGCONT"; exit 1; }
snapshot > "$WORK/after.prom"

python3 - "$WORK/before.prom" "$WORK/after.prom" <<'PY'
import sys
from pathlib import Path

sys.path.insert(0, "scripts")
from check_rpc_metric_evidence import count, parse_samples

before = parse_samples(Path(sys.argv[1]).read_text(encoding="utf-8"))
after = parse_samples(Path(sys.argv[2]).read_text(encoding="utf-8"))
name = "otryx_rpc_client_timeouts_total"
timeouts = count(after, name) - count(before, name)
if timeouts < 1:
    raise SystemExit("FAIL: paused Provider RPC did not emit a deadline timeout counter")
print(f"PASS: real paused-Provider RPC timeout delta={timeouts:g}; Metadata resumed normally")
PY
