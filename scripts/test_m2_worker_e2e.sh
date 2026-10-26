#!/usr/bin/env bash
# Real CI-only Worker SDK: Nacos Consumer -> Dispatcher RPC -> allowlisted Handler -> fenced completion.
set -euo pipefail
: "${FLOW_M2_PROJECT_ID:?Missing project ID}"
: "${FLOW_M2_JOB_ID:?Missing published Job ID}"
: "${FLOW_M2_COOKIE:?Missing session cookie}"
: "${FLOW_M2_TOKEN:?Missing CSRF token}"
: "${FLOW_DB_ROOT_PASSWORD:?Missing DB credentials}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
URL="$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/schedules"
KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
cat > "$WORK/schedule.json" <<JSON
{"jobId":"$FLOW_M2_JOB_ID","jobRevision":2,"expression":"*/5 * * * * *","timeZone":"Etc/UTC","misfirePolicy":"FIRE_ONCE"}
JSON
code="$(curl --max-time 15 -sS -o "$WORK/schedule-response.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $KEY" -d @"$WORK/schedule.json" "$URL")"
[ "$code" = 201 ] || { echo "Worker test schedule creation expected 201, got $code"; cat "$WORK/schedule-response.json"; exit 1; }
SCHEDULE_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/schedule-response.json")"

# No Worker is enabled during the earlier Dispatcher fencing probe. Start it only now.
docker compose --profile worker-demo up -d flow-worker-demo
ready=0
for i in $(seq 1 90); do
  if docker compose --profile worker-demo logs --no-color --tail=100 flow-worker-demo | grep -q 'Started FlowWorkerQuickstartApplication in'; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { docker compose --profile worker-demo logs --tail=100 flow-worker-demo; echo 'Worker did not register in Nacos'; exit 1; }
mysql_query() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot flow_dispatcher -e "$1"' _ "$1"
}
completed=0
for i in $(seq 1 75); do
  status="$(mysql_query "SELECT COUNT(*) FROM flow_dispatch_task WHERE schedule_id='$SCHEDULE_ID' AND status='SUCCEEDED'")"
  audited="$(mysql_query "SELECT COUNT(*) FROM flow_dispatch_attempt a JOIN flow_dispatch_task t ON a.task_id=t.id WHERE t.schedule_id='$SCHEDULE_ID' AND a.worker_id='flow-worker-demo' AND a.status='SUCCEEDED'")"
  if [[ "$status" =~ ^[0-9]+$ && "$status" -gt 0 && "$audited" -gt 0 ]]; then completed=1; break; fi
  sleep 2
done
if [ "$completed" -ne 1 ]; then
  docker compose --profile worker-demo logs --no-color --tail=100 flow-worker-demo
  echo "No real Worker completion recorded: succeeded=$status audited=$audited"
  exit 1
fi
# Validate a truthful persisted Worker attempt and do not call the CI-only Dispatcher probe.
lease_token_leak="$(curl --max-time 15 -sS -b "$FLOW_M2_COOKIE" "$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/dispatch-tasks" | grep -ic 'leaseToken' || true)"
[ "$lease_token_leak" = 0 ] || { echo 'Browser API leaked worker lease token'; exit 1; }
# Stop the worker after the run to avoid interfering with other tests.
docker compose --profile worker-demo stop flow-worker-demo
echo 'PASS: real Nacos Worker SDK consumed allowlisted safe.echo, completed lease and persisted SUCCEEDED attempt'
echo 'NOTE: Dispatcher terminal status is verified; Engine outcome reconciliation is reserved for Issue #9.'
