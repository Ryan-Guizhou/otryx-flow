#!/usr/bin/env bash
# Run as a child of test_m1_e2e.sh against real REST -> RPC -> MySQL services.
set -euo pipefail
: "${FLOW_M2_COOKIE:?Missing E2E session cookie path}"
: "${FLOW_M2_TOKEN:?Missing CSRF token}"
: "${FLOW_M2_PROJECT_ID:?Missing persisted project UUID}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
TEMP="$(mktemp -d)"; trap 'rm -rf "$TEMP"' EXIT
PATH_JOBS="$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/jobs"
KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
printf '{"name":"m2 E2E job","handlerKey":"safe.echo","parametersJson":"{}","timeoutSeconds":30,"maxAttempts":2}' > "$TEMP/draft.json"
code="$(curl --max-time 12 -sS -o "$TEMP/created.json" -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -H "Idempotency-Key: $KEY" -d @"$TEMP/draft.json" "$PATH_JOBS")"
[ "$code" = 201 ] || { echo "Job create expected 201, got $code"; cat "$TEMP/created.json"; exit 1; }
JOB_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$TEMP/created.json")"
code="$(curl --max-time 12 -sS -o "$TEMP/retry.json" -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -H "Idempotency-Key: $KEY" -d @"$TEMP/draft.json" "$PATH_JOBS")"
[ "$code" = 200 ] || { echo "Job idempotent replay expected 200, got $code"; exit 1; }
python3 -c 'import json,sys;a=json.load(open(sys.argv[1]));b=json.load(open(sys.argv[2]));assert a["id"]==b["id"]' "$TEMP/created.json" "$TEMP/retry.json"

printf '{"expectedDraftVersion":1}' > "$TEMP/version1.json"
PUBLISH_KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
code="$(curl --max-time 12 -sS -o "$TEMP/published1.json" -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -H "Idempotency-Key: $PUBLISH_KEY" -d @"$TEMP/version1.json" "$PATH_JOBS/$JOB_ID/publish")"
[ "$code" = 200 ] || { echo "Job publish r1 expected 200, got $code"; exit 1; }
curl --max-time 12 -fsS -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" "$PATH_JOBS/$JOB_ID/revisions/1" > "$TEMP/revision1.json"
python3 -c 'import json,sys;r=json.load(open(sys.argv[1]));assert r["revision"]==1 and r["name"]=="m2 E2E job"' "$TEMP/revision1.json"

printf '{"expectedDraftVersion":1,"name":"m2 E2E revised","handlerKey":"safe.echo","parametersJson":"{\\"revision\\":2}","timeoutSeconds":60,"maxAttempts":2}' > "$TEMP/update.json"
code="$(curl --max-time 12 -sS -o "$TEMP/updated.json" -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" -X PUT -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -d @"$TEMP/update.json" "$PATH_JOBS/$JOB_ID/draft")"
[ "$code" = 200 ] || { echo "Job draft edit expected 200, got $code"; cat "$TEMP/updated.json"; exit 1; }
code="$(curl --max-time 12 -sS -o /dev/null -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" -X PUT -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -d @"$TEMP/update.json" "$PATH_JOBS/$JOB_ID/draft")"
[ "$code" = 409 ] || { echo "Stale draft version expected 409, got $code"; exit 1; }
printf '{"expectedDraftVersion":2}' > "$TEMP/version2.json"
PUBLISH_KEY2="$(python3 -c 'import uuid;print(uuid.uuid4())')"
code="$(curl --max-time 12 -sS -o "$TEMP/published2.json" -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -H "Idempotency-Key: $PUBLISH_KEY2" -d @"$TEMP/version2.json" "$PATH_JOBS/$JOB_ID/publish")"
[ "$code" = 200 ] || { echo "Job publish r2 expected 200, got $code"; exit 1; }
curl --max-time 12 -fsS -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" "$PATH_JOBS/$JOB_ID/revisions/1" > "$TEMP/still-r1.json"
curl --max-time 12 -fsS -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" "$PATH_JOBS/$JOB_ID/revisions/2" > "$TEMP/revision2.json"
python3 -c 'import json,sys;a=json.load(open(sys.argv[1]));b=json.load(open(sys.argv[2]));assert a["name"]=="m2 E2E job" and b["name"]=="m2 E2E revised" and b["revision"]==2' "$TEMP/still-r1.json" "$TEMP/revision2.json"
if [ "${FLOW_E2E_RESTART_METADATA:-0}" = 1 ]; then
  docker compose restart flow-metadata
  available=0
  for round in $(seq 1 45); do
    code="$(curl --max-time 10 -sS -o "$TEMP/after-restart.json" -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" "$PATH_JOBS/$JOB_ID/revisions/2" || true)"
    if [ "$code" = 200 ] && python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["revision"]==2' "$TEMP/after-restart.json" 2>/dev/null; then available=1; break; fi
    sleep 2
  done
  [ "$available" = 1 ] || { echo 'Job revision did not recover after restart'; exit 1; }
fi

# Cron preview remains pure and does not create a scheduler entry.
cat > "$TEMP/cron-request.json" <<'EOF_CRON'
{"expression":"0 30 2 * * *","timeZone":"Europe/Berlin","fromUtc":"2026-03-28T00:00:00Z","limit":3}
EOF_CRON
code="$(curl --max-time 12 -sS -o "$TEMP/cron-preview.json" -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -d @"$TEMP/cron-request.json" "$BASE/api/v1/schedules/preview")"
[ "$code" = 200 ] || { echo "Cron preview expected 200, got $code"; cat "$TEMP/cron-preview.json"; exit 1; }
python3 -c 'import json,sys;p=json.load(open(sys.argv[1]));v=[r["utc"] for r in p["occurrences"]];assert v[0]=="2026-03-28T01:30:00Z" and v[1]=="2026-03-30T00:30:00Z"' "$TEMP/cron-preview.json"
code="$(curl --max-time 12 -sS -o /dev/null -w '%{http_code}' -c "$FLOW_M2_COOKIE" -b "$FLOW_M2_COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -d '{"expression":"* * *","timeZone":"Etc/UTC","fromUtc":"2026-03-28T00:00:00Z","limit":3}' \
  "$BASE/api/v1/schedules/preview")"
[ "$code" = 400 ] || { echo "Malformed Cron expected 400, got $code"; exit 1; }
echo 'PASS: pure Cron + IANA zone + DST gap preview (not a scheduled trigger)'

echo 'PASS: M2 persisted draft + stable idempotency + CAS + immutable revisions + RPC (and optional restart)'
