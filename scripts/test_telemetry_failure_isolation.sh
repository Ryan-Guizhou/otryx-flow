#!/usr/bin/env bash
# Isolated real Nacos + MySQL + OTRYX RPC test: Collector offline during a leased Worker task.
set -euo pipefail
: "${FLOW_ADMIN_USERNAME:?Missing admin username}"
: "${FLOW_ADMIN_PASSWORD:?Missing admin password}"
: "${FLOW_DB_ROOT_PASSWORD:?Missing database credentials}"
[ "${FLOW_E2E_DELAY_HANDLER:-false}" = "true" ] || {
  echo "Refusing fault injection without the isolated CI delay handler"; exit 1;
}
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
restore() {
  docker compose up -d --no-deps otel-collector >/dev/null || true
  rm -rf "$WORK"
}
trap restore EXIT
COOKIE="$WORK/cookie"
mysql_query() {
  docker compose exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' \
    _ "$1" "$2"
}
api_write() {
  local endpoint="$1" input="$2" output="$3" expected="$4"
  local http_status
  http_status="$(curl --silent --show-error --max-time 20 -o "$output" -w '%{http_code}' \
    -b "$COOKIE" -c "$COOKIE" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $TOKEN" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" \
    --data-binary @"$input" "$endpoint")"
  [ "$http_status" = "$expected" ] || {
    echo "Isolated API write returned HTTP $http_status, expected $expected"; exit 1;
  }
}

# Restart the test-only Worker first, while dependencies are healthy. Never use
# docker compose up for a dependent service after stopping the Collector.
docker compose --profile worker-demo up -d --no-deps flow-worker-demo
ready=0
for i in $(seq 1 45); do
  if docker compose --profile worker-demo logs --no-color flow-worker-demo \
    | grep -F 'Started FlowWorkerQuickstartApplication in' >/dev/null; then
    ready=1
    break
  fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "Worker did not start before Collector outage"; exit 1; }

docker compose stop otel-collector
# A manual stop must remain stopped (the service has an automatic restart policy).
[ "$(docker inspect -f '{{.State.Running}}' "$(docker compose ps -q -a otel-collector)")" = false ] || {
  echo "Collector was not stopped"; exit 1;
}
echo "Collector stopped; execute real REST, RPC, Scheduler and Worker operations."

curl --fail --silent --show-error --max-time 15 \
  -b "$COOKIE" -c "$COOKIE" "$BASE/api/v1/auth/csrf" > "$WORK/csrf.json"
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$WORK/csrf.json")"
python3 -c 'import json,os,sys;json.dump({"username":os.environ["FLOW_ADMIN_USERNAME"],"password":os.environ["FLOW_ADMIN_PASSWORD"]},open(sys.argv[1],"w"))' "$WORK/login.json"
api_write "$BASE/api/v1/auth/login" "$WORK/login.json" "$WORK/login-response.json" 200
printf '{"name":"otel-offline-e2e-%s"}' "$(date +%s)" > "$WORK/project.json"
api_write "$BASE/api/v1/projects" "$WORK/project.json" "$WORK/project-response.json" 201
PROJECT_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/project-response.json")"

printf '%s' '{"name":"OTLP isolated delay","handlerKey":"safe.delay","parametersJson":"{}","timeoutSeconds":60,"maxAttempts":1}' > "$WORK/job.json"
api_write "$BASE/api/v1/projects/$PROJECT_ID/jobs" "$WORK/job.json" "$WORK/job-response.json" 201
JOB_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/job-response.json")"
printf '%s' '{"expectedDraftVersion":1}' > "$WORK/publish.json"
api_write "$BASE/api/v1/projects/$PROJECT_ID/jobs/$JOB_ID/publish" \
  "$WORK/publish.json" "$WORK/publish-response.json" 200
printf '{"jobId":"%s","jobRevision":1,"expression":"*/5 * * * * *","timeZone":"Etc/UTC","misfirePolicy":"FIRE_ONCE"}' "$JOB_ID" > "$WORK/schedule.json"
api_write "$BASE/api/v1/projects/$PROJECT_ID/schedules" \
  "$WORK/schedule.json" "$WORK/schedule-response.json" 201
SCHEDULE_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/schedule-response.json")"

# Prove a real lease was acquired while Collector remained offline.
TASK_ID=""
for i in $(seq 1 90); do
  TASK_ID="$(mysql_query flow_dispatcher \
    "SELECT id FROM flow_dispatch_task WHERE schedule_id='$SCHEDULE_ID' AND status='LEASED' LIMIT 1" || true)"
  [ -n "$TASK_ID" ] && break
  sleep 1
done
[ -n "$TASK_ID" ] || { echo "No lease acquired during OTLP outage"; exit 1; }
INITIAL_DEADLINE="$(mysql_query flow_dispatcher \
  "SELECT lease_until FROM flow_dispatch_task WHERE id='$TASK_ID'")"
[ -n "$INITIAL_DEADLINE" ] || { echo "Missing initial Worker lease"; exit 1; }
HEARTBEAT=0
for i in $(seq 1 10); do
  extended="$(mysql_query flow_dispatcher \
    "SELECT COUNT(*) FROM flow_dispatch_task WHERE id='$TASK_ID' AND lease_until > '$INITIAL_DEADLINE'")"
  if [ "$extended" = 1 ]; then HEARTBEAT=1; break; fi
  sleep 1
done
[ "$HEARTBEAT" = 1 ] || { echo "No persisted Worker heartbeat lease extension during OTLP outage"; exit 1; }
echo "PASS: independent Worker heartbeat extended the real Dispatcher lease while Collector was offline."

RUN_ID="$(mysql_query flow_dispatcher \
  "SELECT engine_run_id FROM flow_dispatch_task WHERE id='$TASK_ID'")"
finished=0
for i in $(seq 1 90); do
  DISPATCH="$(mysql_query flow_dispatcher \
    "SELECT status FROM flow_dispatch_task WHERE id='$TASK_ID'" || true)"
  ENGINE="$(mysql_query flow_engine \
    "SELECT status FROM flow_workflow_run WHERE id='$RUN_ID'" || true)"
  OUTBOX="$(mysql_query flow_dispatcher \
    "SELECT delivery_state FROM flow_dispatch_outcome_outbox WHERE engine_run_id='$RUN_ID'" || true)"
  if [ "$DISPATCH" = SUCCEEDED ] && [ "$ENGINE" = SUCCEEDED ] && [ "$OUTBOX" = DELIVERED ]; then
    finished=1
    break
  fi
  sleep 2
done
[ "$finished" = 1 ] || {
  echo "Terminal outcome was affected by OTLP outage: dispatch=$DISPATCH engine=$ENGINE outbox=$OUTBOX"
  exit 1
}
COUNT="$(mysql_query flow_dispatcher \
  "SELECT COUNT(*) FROM flow_dispatch_attempt WHERE task_id='$TASK_ID' AND status='SUCCEEDED'")"
[ "$COUNT" = 1 ] || { echo "Expected exactly one persisted successful Worker attempt"; exit 1; }

# The endpoint remains internal. Prometheus, not host-local access, detects the down scrape target.
down=0
for i in $(seq 1 12); do
  response="$(docker compose --profile observability-test run --rm --no-deps \
    --entrypoint sh observability-probe -ec \
    'curl --silent --show-error --fail --get --data-urlencode "query=up{job=\"otel-collector\"}" http://prometheus:9090/api/v1/query')"
  if printf '%s' "$response" | python3 -c \
    'import json,sys;items=json.load(sys.stdin)["data"]["result"];assert items and any(x["value"][1]=="0" for x in items)' \
    >/dev/null 2>&1; then
    down=1
    break
  fi
  sleep 3
done
[ "$down" = 1 ] || { echo "Prometheus did not record Collector as unavailable"; exit 1; }
code="$(curl --silent --show-error --max-time 15 -o /dev/null -w '%{http_code}' \
  -b "$COOKIE" "$BASE/api/v1/projects")"
[ "$code" = 200 ] || { echo "Business API failed while OTLP exporter was unavailable"; exit 1; }
echo "PASS: Collector outage detected, while real RPC, Worker heartbeat, terminal Outbox/Inbox and HTTP API survived."
