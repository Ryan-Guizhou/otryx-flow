#!/usr/bin/env bash
# Real, isolated Nacos Compose E2E: Worker -> Dispatcher terminal Outbox -> Engine Inbox -> Run.
set -euo pipefail
: "${FLOW_M2_COOKIE:?Missing session cookie}"
: "${FLOW_M2_TOKEN:?Missing CSRF token}"
: "${FLOW_M2_PROJECT_ID:?Missing project UUID}"
: "${FLOW_M2_WORKER_SCHEDULE_ID:?Missing successful Worker test schedule}"
: "${FLOW_DB_ROOT_PASSWORD:?Missing MySQL credentials}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
sql() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' _ "$1" "$2"
}
# A real Worker has already completed one safe.echo task in the parent script.
RUN="$(sql flow_dispatcher "SELECT engine_run_id FROM flow_dispatch_task WHERE schedule_id='$FLOW_M2_WORKER_SCHEDULE_ID' AND status='SUCCEEDED' ORDER BY created_at LIMIT 1")"
[ -n "$RUN" ] || { echo 'No genuinely completed Worker run available'; exit 1; }
ready=0
for i in $(seq 1 60); do
  status="$(sql flow_engine "SELECT status FROM flow_workflow_run WHERE id='$RUN'" || true)"
  delivery="$(sql flow_dispatcher "SELECT delivery_state FROM flow_dispatch_outcome_outbox WHERE engine_run_id='$RUN'" || true)"
  if [ "$status" = SUCCEEDED ] && [ "$delivery" = DELIVERED ]; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "Outcome delivery incomplete: run=$RUN status=$status delivery=$delivery"; exit 1; }
count="$(sql flow_engine "SELECT COUNT(*) FROM flow_engine_outcome_inbox WHERE engine_run_id='$RUN'")"
[ "$count" = 1 ] || { echo "Expected exactly one Engine outcome Inbox record, got $count"; exit 1; }
# Simulate an ACK lost after Engine commit: Dispatcher must redeliver same eventId with no duplicate Run.
sql flow_dispatcher "UPDATE flow_dispatch_outcome_outbox SET delivery_state='PENDING', claim_token=NULL, lease_until=NULL, next_attempt_at='1970-01-01 00:00:00.000', delivered_at=NULL WHERE engine_run_id='$RUN'"
replayed=0
for i in $(seq 1 60); do
  status="$(sql flow_engine "SELECT status FROM flow_workflow_run WHERE id='$RUN'" || true)"
  delivery="$(sql flow_dispatcher "SELECT delivery_state FROM flow_dispatch_outcome_outbox WHERE engine_run_id='$RUN'" || true)"
  if [ "$status" = SUCCEEDED ] && [ "$delivery" = DELIVERED ]; then replayed=1; break; fi
  sleep 2
done
[ "$replayed" = 1 ] || { echo 'Outcome ACK redelivery failed'; exit 1; }
count="$(sql flow_engine "SELECT COUNT(*) FROM flow_engine_outcome_inbox WHERE engine_run_id='$RUN'")"
[ "$count" = 1 ] || { echo 'ACK redelivery created duplicate Engine Inbox'; exit 1; }
echo 'PASS: real Worker succeeded, terminal Outcome Outbox delivered; identical event ACK loss replay idempotent'

# Dedicated fail-on-purpose trusted Handler verifies bounded retry budget and final FAILED Engine Run.
URL="$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/jobs"
KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
printf '%s' '{"name":"terminal failure E2E","handlerKey":"safe.fail","parametersJson":"{}","timeoutSeconds":20,"maxAttempts":2}' > "$WORK/fail-job.json"
code="$(curl --max-time 15 -sS -o "$WORK/job.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -H "Idempotency-Key: $KEY" --data-binary @"$WORK/fail-job.json" "$URL")"
[ "$code" = 201 ] || { echo "Fail-job creation expected 201, got $code"; cat "$WORK/job.json"; exit 1; }
JOB="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/job.json")"
code="$(curl --max-time 15 -sS -o "$WORK/published.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" --data '{"expectedDraftVersion":1}' "$URL/$JOB/publish")"
[ "$code" = 200 ] || { echo "Fail-job publish expected 200, got $code"; exit 1; }
KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
printf '{"jobId":"%s","jobRevision":1,"expression":"*/5 * * * * *","timeZone":"Etc/UTC","misfirePolicy":"FIRE_ONCE"}' "$JOB" > "$WORK/fail-schedule.json"
code="$(curl --max-time 15 -sS -o "$WORK/schedule.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" -H "Idempotency-Key: $KEY" --data-binary @"$WORK/fail-schedule.json" "$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/schedules")"
[ "$code" = 201 ] || { echo "Fail schedule create expected 201, got $code"; cat "$WORK/schedule.json"; exit 1; }
SCHEDULE="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/schedule.json")"
docker compose --profile worker-demo up -d flow-worker-demo
failed=0
for i in $(seq 1 90); do
  RUN_FAILED="$(sql flow_dispatcher "SELECT engine_run_id FROM flow_dispatch_task WHERE schedule_id='$SCHEDULE' AND status='FAILED' ORDER BY created_at LIMIT 1" || true)"
  if [ -n "$RUN_FAILED" ]; then
    ENGINE_STATUS="$(sql flow_engine "SELECT status FROM flow_workflow_run WHERE id='$RUN_FAILED'" || true)"
    OUTBOX="$(sql flow_dispatcher "SELECT delivery_state FROM flow_dispatch_outcome_outbox WHERE engine_run_id='$RUN_FAILED'" || true)"
    if [ "$ENGINE_STATUS" = FAILED ] && [ "$OUTBOX" = DELIVERED ]; then failed=1; break; fi
  fi
  sleep 2
done
[ "$failed" = 1 ] || { echo "Failed Worker did not reconcile Engine: run=${RUN_FAILED:-missing} status=${ENGINE_STATUS:-missing} outbox=${OUTBOX:-missing}"; exit 1; }
ATTEMPTS="$(sql flow_dispatcher "SELECT COUNT(*) FROM flow_dispatch_attempt a JOIN flow_dispatch_task t ON a.task_id=t.id WHERE t.engine_run_id='$RUN_FAILED' AND a.status='FAILED'")"
[ "$ATTEMPTS" = 2 ] || { echo "Expected two bounded failing Handler attempts, got $ATTEMPTS"; exit 1; }
ENGINE_COUNT="$(sql flow_engine "SELECT COUNT(*) FROM flow_engine_outcome_inbox WHERE engine_run_id='$RUN_FAILED'")"
[ "$ENGINE_COUNT" = 1 ] || { echo "Expected one Engine failure Inbox event, got $ENGINE_COUNT"; exit 1; }
docker compose --profile worker-demo stop flow-worker-demo
echo 'PASS: trusted failing Handler retried only twice, Dispatch terminal FAILED -> Engine Inbox + FAILED Run'
echo 'PASS: complete M2 durable short-task chain on Nacos; full DAG and production mTLS remain future scope'
