#!/usr/bin/env bash
# Verify real application-level OTRYX RPC metrics using internal management snapshots.
set -euo pipefail
: "${FLOW_ADMIN_USERNAME:?Missing admin username}"
: "${FLOW_ADMIN_PASSWORD:?Missing admin password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
COOKIE="$WORK/cookie"

snapshot() {
  docker compose --profile observability-test run --rm --no-deps \
    --entrypoint sh observability-probe -ec \
    'curl --fail --silent --show-error --max-time 10 http://flow-api:9091/actuator/prometheus'
}

snapshot > "$WORK/before.prom"

curl --fail --silent --show-error --max-time 15 \
  -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/auth/csrf" > "$WORK/csrf.json"
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$WORK/csrf.json")"
python3 -c 'import json,os,sys;json.dump({"username":os.environ["FLOW_ADMIN_USERNAME"],"password":os.environ["FLOW_ADMIN_PASSWORD"]},open(sys.argv[1],"w"))' "$WORK/login.json"
code="$(curl --silent --show-error --max-time 15 -o "$WORK/login-response.json" -w '%{http_code}' \
  -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $TOKEN" --data-binary @"$WORK/login.json" "$BASE/api/v1/auth/login")"
[ "$code" = "200" ] || { echo "Metrics E2E login failed: HTTP $code"; exit 1; }

for i in $(seq 1 5); do
  code="$(curl --silent --show-error --max-time 15 -o "$WORK/projects.json" -w '%{http_code}' \
    -b "$COOKIE" "$BASE/api/v1/projects")"
  [ "$code" = "200" ] || { echo "Controlled OTRYX RPC call failed: HTTP $code"; exit 1; }
done

snapshot > "$WORK/after.prom"
python3 scripts/check_rpc_metric_evidence.py \
  --before "$WORK/before.prom" --after "$WORK/after.prom" --min-success 5
echo "PASS: live OTRYX RPC client timers use real network calls and bounded labels"
