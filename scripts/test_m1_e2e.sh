#!/usr/bin/env bash
# Tests the real REST -> OTRYX RPC -> metadata database path; requires running Compose.
set -euo pipefail
: "${FLOW_ADMIN_USERNAME:?Set admin username}"
: "${FLOW_ADMIN_PASSWORD:?Set admin password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
TEMP="$(mktemp -d)"; trap 'rm -rf "$TEMP"' EXIT
COOKIE="$TEMP/cookie"
for round in $(seq 1 30); do
  if curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -f "$BASE/api/v1/auth/csrf"; then break; fi
  sleep 2
done
unauth="$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/projects")"
[ "$unauth" = "401" ] || { echo "Expected 401 without session, got $unauth"; exit 1; }
curl --connect-timeout 3 --max-time 10 -sS -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/auth/csrf" > "$TEMP/csrf.json"
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$TEMP/csrf.json")"
python3 -c 'import os,json,sys;json.dump({"username":os.environ["FLOW_ADMIN_USERNAME"],"password":os.environ["FLOW_ADMIN_PASSWORD"]},open(sys.argv[1],"w"))' "$TEMP/login.json"
code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$TEMP/user.json" -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" -d @"$TEMP/login.json" "$BASE/api/v1/auth/login")"
[ "$code" = "200" ] || { echo "Login failed with HTTP $code"; exit 1; }
curl --connect-timeout 3 --max-time 10 -sS -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/auth/csrf" > "$TEMP/csrf.json"
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$TEMP/csrf.json")"
# Allow bounded time for the async application directory refresh on initial startup.
for round in $(seq 1 30); do
  code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$TEMP/projects.json" -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/projects")"
  if [ "$code" = "200" ]; then break; fi
  sleep 2
done
[ "$code" = "200" ] || { echo "RPC list did not become available, got $code"; exit 1; }
key="$(python3 -c 'import uuid;print(uuid.uuid4())')"
name="e2e-m1-$(date +%s)"
printf '{"name":"%s"}' "$name" > "$TEMP/create.json"
code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$TEMP/create-result.json" -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" -H "Idempotency-Key: $key" -d @"$TEMP/create.json" "$BASE/api/v1/projects")"
[ "$code" = "201" ] || { echo "Expected 201, got $code"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$TEMP/create-retry.json" -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" -H "Idempotency-Key: $key" -d @"$TEMP/create.json" "$BASE/api/v1/projects")"
[ "$code" = "200" ] || { echo "Expected 200 idempotent replay, got $code"; exit 1; }
python3 -c 'import json,sys;a=json.load(open(sys.argv[1]));b=json.load(open(sys.argv[2]));assert a["id"]==b["id"],"Duplicate project created"' "$TEMP/create-result.json" "$TEMP/create-retry.json"
curl --connect-timeout 3 --max-time 10 -sS -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/projects" > "$TEMP/after.json"
python3 -c 'import json,sys;r=json.load(open(sys.argv[1]));p=json.load(open(sys.argv[2]));assert sum(row["id"]==p["id"] for row in r)==1,"Project not persisted exactly once"' "$TEMP/after.json" "$TEMP/create-result.json"
code="$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: invalid" -H "Idempotency-Key: $key" -d @"$TEMP/create.json" "$BASE/api/v1/projects")"
[ "$code" = "403" ] || { echo "Expected CSRF rejection 403, got $code"; exit 1; }
# An identical key cannot be reassigned to a different body.
printf '{"name":"conflicting payload"}' > "$TEMP/conflict.json"
code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$TEMP/conflict-result.json" -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -H "Idempotency-Key: $key" -d @"$TEMP/conflict.json" "$BASE/api/v1/projects")"
[ "$code" = "409" ] || { echo "Expected 409 on changed payload with reused key, got $code"; exit 1; }
python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["code"] == "IDEMPOTENCY_CONFLICT"' "$TEMP/conflict-result.json"

# An invalid key is rejected before reaching the metadata provider.
code="$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -H 'Idempotency-Key: short' -d @"$TEMP/create.json" "$BASE/api/v1/projects")"
[ "$code" = "400" ] || { echo "Expected 400 on invalid key, got $code"; exit 1; }

if [ "${FLOW_E2E_RESTART_METADATA:-0}" = "1" ]; then
  # Intentional fault injection is only allowed when explicitly requested.
  command -v docker >/dev/null || { echo "Restart requested but Docker is unavailable"; exit 1; }
  docker compose restart flow-metadata
  restored=0
  for round in $(seq 1 45); do
    code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$TEMP/restored.json" -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/projects" || true)"
    if [ "$code" = "200" ] && python3 -c 'import json,sys;r=json.load(open(sys.argv[1]));p=json.load(open(sys.argv[2]));assert sum(row["id"] == p["id"] for row in r) == 1' "$TEMP/restored.json" "$TEMP/create-result.json" 2>/dev/null; then
      restored=1
      break
    fi
    sleep 2
  done
  [ "$restored" = "1" ] || { echo 'Persisted project not visible after metadata restart'; exit 1; }
fi

if [ "${FLOW_E2E_M2_JOBS:-0}" = "1" ]; then
  FLOW_M2_COOKIE="$COOKIE" FLOW_M2_TOKEN="$TOKEN" \
    FLOW_M2_PROJECT_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$TEMP/create-result.json")" \
    bash scripts/test_m2_job_e2e.sh
fi

# Logout destroys the HTTP session; the same cookie must no longer authorize reads.
code="$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" \
  -H "X-XSRF-TOKEN: $TOKEN" -X POST "$BASE/api/v1/auth/logout")"
[ "$code" = "204" ] || { echo "Expected logout 204, got $code"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/projects")"
[ "$code" = "401" ] || { echo "Expected 401 after logout, got $code"; exit 1; }
echo 'PASS: session auth + CSRF + OTRYX RPC + durable idempotency + logout'
if [ "${FLOW_E2E_RESTART_METADATA:-0}" = "1" ]; then
  echo 'PASS: metadata restart recovery + persisted project visibility'
fi
