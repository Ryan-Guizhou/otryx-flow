#!/usr/bin/env bash
# Real Compose acceptance for ordinary Job cancellation, Retry input preservation, and disabled-job fencing.
set -euo pipefail
: "${FLOW_M2_COOKIE:?Missing authenticated session cookie}"
: "${FLOW_M2_TOKEN:?Missing CSRF token}"
: "${FLOW_M2_PROJECT_ID:?Missing project UUID}"
: "${FLOW_DB_ROOT_PASSWORD:?Missing MySQL root password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
JOBS="$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/jobs"

sql() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' _ "$1" "$2"
}

# The test-only handler is published by Metadata and registered by the Worker only in isolated E2E mode.
docker compose --profile worker-demo up -d flow-worker-demo
worker_ready=0
for attempt in $(seq 1 35); do
  curl --connect-timeout 3 --max-time 10 -fsS -b "$FLOW_M2_COOKIE" \
    "$BASE/api/v1/workers" > "$WORK/workers.json" || true
  if python3 -c 'import json,sys; rows=json.load(open(sys.argv[1])); assert any(r["status"]=="ONLINE" and "safe.delay" in r["handlersCsv"].split(",") for r in rows)' "$WORK/workers.json" 2>/dev/null; then
    worker_ready=1
    break
  fi
  sleep 2
done
[ "$worker_ready" = 1 ] || { echo 'No online Worker advertises the E2E safe.delay handler'; exit 1; }

# Create and publish a cancellable Job with an immutable input override.
KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
cat > "$WORK/job.json" <<'EOF_JOB'
{"name":"M2 cancellation control","handlerKey":"safe.delay","parametersJson":"{}","timeoutSeconds":45,"maxAttempts":2}
EOF_JOB
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/job-created.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $KEY" --data-binary @"$WORK/job.json" "$JOBS")"
[ "$code" = 201 ] || { echo "Control Job create expected 201, got $code"; cat "$WORK/job-created.json"; exit 1; }
JOB="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/job-created.json")"
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/job-published.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" \
  --data '{"expectedDraftVersion":1}' "$JOBS/$JOB/publish")"
[ "$code" = 200 ] || { echo "Control Job publish expected 200, got $code"; cat "$WORK/job-published.json"; exit 1; }

# Manual execution is acknowledged only after the Engine has persisted its Run and dispatch Outbox.
cat > "$WORK/start.json" <<'EOF_START'
{"jobRevision":1,"parameterOverrides":"{\"marker\":\"preserve-me\"}"}
EOF_START
RUN_KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/run-started.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $RUN_KEY" --data-binary @"$WORK/start.json" "$JOBS/$JOB/runs")"
[ "$code" = 202 ] || { echo "Manual Run expected 202, got $code"; cat "$WORK/run-started.json"; exit 1; }
RUN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["runId"])' "$WORK/run-started.json")"

leased=0
for attempt in $(seq 1 30); do
  task_state="$(sql flow_dispatcher "SELECT status FROM flow_dispatch_task WHERE engine_run_id='$RUN' LIMIT 1" || true)"
  if [ "$task_state" = "LEASED" ]; then leased=1; break; fi
  sleep 0.25
done
[ "$leased" = 1 ] || { echo "Worker did not lease cancellable Run $RUN (state=${task_state:-missing})"; exit 1; }

RUN_VERSION="$(sql flow_engine "SELECT run_version FROM flow_workflow_run WHERE id='$RUN'")"
CANCEL_KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
printf '{"expectedRunVersion":%s}' "$RUN_VERSION" > "$WORK/cancel.json"
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/cancel.json.out" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $CANCEL_KEY" --data-binary @"$WORK/cancel.json" \
  "$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/job-runs/$RUN/cancel")"
[ "$code" = 202 ] || { echo "Cancel request expected 202, got $code"; cat "$WORK/cancel.json.out"; exit 1; }

cancelled=0
for attempt in $(seq 1 40); do
  engine_state="$(sql flow_engine "SELECT status FROM flow_workflow_run WHERE id='$RUN'" || true)"
  task_state="$(sql flow_dispatcher "SELECT status FROM flow_dispatch_task WHERE engine_run_id='$RUN' LIMIT 1" || true)"
  attempt_state="$(sql flow_dispatcher "SELECT status FROM flow_dispatch_attempt a JOIN flow_dispatch_task t ON t.id=a.task_id WHERE t.engine_run_id='$RUN' ORDER BY a.lease_epoch DESC LIMIT 1" || true)"
  if [ "$engine_state" = "CANCELLED" ] && [ "$task_state" = "CANCELLED" ] && [ "$attempt_state" = "CANCELLED" ]; then
    cancelled=1
    break
  fi
  sleep 1
done
[ "$cancelled" = 1 ] || {
  echo "Cancellation did not converge: engine=${engine_state:-missing} task=${task_state:-missing} attempt=${attempt_state:-missing}"
  exit 1
}
cancel_delivery="$(sql flow_engine "SELECT delivery_state FROM flow_engine_run_cancel_outbox WHERE run_id='$RUN'")"
[ "$cancel_delivery" = "DELIVERED" ] || { echo "Cancel outbox was not acknowledged: $cancel_delivery"; exit 1; }

# Retry is a new Run; its original parameter override must be byte-for-byte preserved.
RETRY_KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/retry.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $RETRY_KEY" -X POST \
  "$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/job-runs/$RUN/retry")"
[ "$code" = 202 ] || { echo "Retry expected 202, got $code"; cat "$WORK/retry.json"; exit 1; }
RETRY_RUN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["runId"])' "$WORK/retry.json")"
original_override="$(sql flow_engine "SELECT parameter_override FROM flow_workflow_run WHERE id='$RUN'")"
retry_override="$(sql flow_engine "SELECT parameter_override FROM flow_workflow_run WHERE id='$RETRY_RUN'")"
[ "$original_override" = "$retry_override" ] || {
  echo "Retry input changed: original=$original_override retry=$retry_override"
  exit 1
}
retry_link="$(sql flow_engine "SELECT retry_of_run_id FROM flow_workflow_run WHERE id='$RETRY_RUN'")"
[ "$retry_link" = "$RUN" ] || { echo "Retry lineage mismatch: $retry_link"; exit 1; }

retry_success=0
for attempt in $(seq 1 60); do
  engine_state="$(sql flow_engine "SELECT status FROM flow_workflow_run WHERE id='$RETRY_RUN'" || true)"
  task_state="$(sql flow_dispatcher "SELECT status FROM flow_dispatch_task WHERE engine_run_id='$RETRY_RUN' LIMIT 1" || true)"
  if [ "$engine_state" = "SUCCEEDED" ] && [ "$task_state" = "SUCCEEDED" ]; then
    retry_success=1
    break
  fi
  sleep 1
done
[ "$retry_success" = 1 ] || {
  echo "Retried Run did not complete: engine=${engine_state:-missing} task=${task_state:-missing}"
  exit 1
}
echo 'PASS: leased Job cancellation converged Worker Attempt, Dispatcher Task and Engine Run'
echo 'PASS: Retry created a new Run and preserved the original parameter override'

# Create a future Cron plan, then disable the Job and force the due cursor to prove no fire is emitted.
SCHEDULE_KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
cat > "$WORK/schedule.json" <<EOF_SCHEDULE
{"jobId":"$JOB","jobRevision":1,"expression":"0 0 0 1 1 *","timeZone":"Etc/UTC","misfirePolicy":"SKIP"}
EOF_SCHEDULE
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/schedule-created.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $SCHEDULE_KEY" --data-binary @"$WORK/schedule.json" \
  "$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/schedules")"
[ "$code" = 201 ] || { echo "Control schedule create expected 201, got $code"; cat "$WORK/schedule-created.json"; exit 1; }
SCHEDULE="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/schedule-created.json")"

code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/disabled.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  --data '{"enabled":false,"expectedDraftVersion":1}' "$JOBS/$JOB/enabled")"
[ "$code" = 200 ] || { echo "Disable Job expected 200, got $code"; cat "$WORK/disabled.json"; exit 1; }
python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["enabled"] is False' "$WORK/disabled.json"

# Force the schedule due only after the Job has been disabled; Scanner must pause it without a Fire or Outbox.
sql flow_scheduler "UPDATE flow_schedule_definition SET next_fire_at_utc='2000-01-01 00:00:00.000' WHERE id='$SCHEDULE'"
paused=0
for attempt in $(seq 1 20); do
  enabled="$(sql flow_scheduler "SELECT enabled FROM flow_schedule_definition WHERE id='$SCHEDULE'" || true)"
  if [ "$enabled" = "0" ]; then paused=1; break; fi
  sleep 1
done
[ "$paused" = 1 ] || { echo 'Scheduler did not pause the Cron plan for a disabled Job'; exit 1; }
triggers="$(sql flow_scheduler "SELECT COUNT(*) FROM flow_schedule_trigger WHERE schedule_id='$SCHEDULE'")"
[ "$triggers" = "0" ] || { echo "Disabled Job emitted Cron triggers: $triggers"; exit 1; }

# Disabled and archived Jobs cannot be manually run or receive new Cron plans.
RUN_KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
code="$(curl --connect-timeout 3 --max-time 12 -sS -o "$WORK/disabled-run.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $RUN_KEY" --data-binary @"$WORK/start.json" "$JOBS/$JOB/runs")"
[ "$code" = 409 ] || { echo "Disabled Job manual Run expected 409, got $code"; cat "$WORK/disabled-run.json"; exit 1; }
SCHEDULE_KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
code="$(curl --connect-timeout 3 --max-time 12 -sS -o "$WORK/disabled-schedule.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $SCHEDULE_KEY" --data-binary @"$WORK/schedule.json" \
  "$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/schedules")"
[ "$code" = 409 ] || { echo "Disabled Job Cron create expected 409, got $code"; cat "$WORK/disabled-schedule.json"; exit 1; }

code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/enabled.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  --data '{"enabled":true,"expectedDraftVersion":2}' "$JOBS/$JOB/enabled")"
[ "$code" = 200 ] || { echo "Re-enable Job expected 200, got $code"; cat "$WORK/enabled.json"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/archived.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  --data '{"expectedDraftVersion":3}' "$JOBS/$JOB/archive")"
[ "$code" = 200 ] || { echo "Archive Job expected 200, got $code"; cat "$WORK/archived.json"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 12 -sS -o "$WORK/archived-run.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" \
  --data-binary @"$WORK/start.json" "$JOBS/$JOB/runs")"
[ "$code" = 409 ] || { echo "Archived Job manual Run expected 409, got $code"; cat "$WORK/archived-run.json"; exit 1; }
echo 'PASS: disabled Job emitted no Cron Fire; disabled and archived Jobs reject manual and scheduled execution'
