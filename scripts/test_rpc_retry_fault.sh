#!/usr/bin/env bash
# CI-only: terminate a Provider to verify real idempotent OTRYX retry accounting and recovery.
set -euo pipefail
[ "${FLOW_E2E_RPC_RETRY_FAULT:-0}" = 1 ] || {
  echo 'Refusing retry fault injection outside isolated CI'; exit 1;
}
: "${FLOW_ADMIN_USERNAME:?Missing admin username}"
: "${FLOW_ADMIN_PASSWORD:?Missing admin password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
KILLED=0

restore() {
  if [ "$KILLED" = 1 ]; then
    docker compose up -d --no-deps flow-metadata >/dev/null 2>&1 || true
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
[ "$code" = 200 ] || { echo "Retry fault E2E login failed: HTTP $code"; exit 1; }
code="$(curl --silent --show-error --max-time 15 -o /dev/null -w '%{http_code}' \
  -b "$COOKIE" "$BASE/api/v1/projects")"
[ "$code" = 200 ] || { echo "Metadata RPC unhealthy before fault: HTTP $code"; exit 1; }

snapshot > "$WORK/before.prom"
CID="$(docker compose ps -q flow-metadata)"
[ -n "$CID" ] || { echo "Missing live Metadata container"; exit 1; }
KILLED=1
docker kill --signal=KILL "$CID" >/dev/null
# SIGKILL may initially leave a stale Nacos endpoint. A transient transport error
# is not automatically equivalent to a scheduled retry. Probe after Nacos marks the
# application member absent and verify that the idempotent directory-failure path
# produces a real, bounded ClientRetryController decision.
retries() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path

sys.path.insert(0, "scripts")
from check_rpc_metric_evidence import count, parse_samples

samples = parse_samples(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(int(count(samples, "otryx_rpc_client_retries_total")))
PY
}
start_retries="$(retries "$WORK/before.prom")"
retry_observed=0
for i in $(seq 1 25); do
  code="$(curl --silent --show-error --max-time 12 -o "$WORK/during.json" -w '%{http_code}' \
    -b "$COOKIE" "$BASE/api/v1/projects" || true)"
  [ "$code" != 200 ] || { echo "Killed Metadata Provider unexpectedly returned HTTP 200"; exit 1; }
  snapshot > "$WORK/after-failure.prom"
  current_retries="$(retries "$WORK/after-failure.prom")"
  if [ "$current_retries" -gt "$start_retries" ]; then
    retry_observed=1
    break
  fi
  sleep 2
done
[ "$retry_observed" = 1 ] || {
  echo "FAIL: no real RPC retry after 25 idempotent requests during Provider loss"; exit 1;
}

docker compose up -d --no-deps flow-metadata
KILLED=0
restored=0
for i in $(seq 1 60); do
  code="$(curl --silent --show-error --max-time 10 -o /dev/null -w '%{http_code}' \
    -b "$COOKIE" "$BASE/api/v1/projects" || true)"
  if [ "$code" = 200 ]; then restored=1; break; fi
  sleep 2
done
[ "$restored" = 1 ] || { echo "Metadata RPC did not recover after SIGKILL"; exit 1; }

python3 - "$WORK/before.prom" "$WORK/after-failure.prom" <<'PY'
import sys
from pathlib import Path

sys.path.insert(0, "scripts")
from check_rpc_metric_evidence import count, parse_samples

before = parse_samples(Path(sys.argv[1]).read_text(encoding="utf-8"))
after = parse_samples(Path(sys.argv[2]).read_text(encoding="utf-8"))
name = "otryx_rpc_client_retries_total"
delta = count(after, name) - count(before, name)
if delta < 1:
    raise SystemExit("FAIL: real stopped Provider did not schedule an OTRYX client retry")
print(f"PASS: real idempotent RPC retry counter delta={delta:g}; Metadata rejoined normally")
PY
