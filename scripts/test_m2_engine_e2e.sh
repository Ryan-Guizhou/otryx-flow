#!/usr/bin/env bash
# Real Compose validation: Scheduler Outbox -> OTRYX RPC -> Engine Inbox + durable logical Run.
set -euo pipefail
: "${FLOW_M2_COOKIE:?Missing authenticated test cookie}"
: "${FLOW_M2_PROJECT_ID:?Missing project ID}"
: "${FLOW_M2_SCHEDULE_ID:?Missing schedule ID}"
: "${FLOW_DB_ROOT_PASSWORD:?Missing MySQL test root password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
query() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' _ "$1" "$2"
}
# The scheduler has been paused by the parent script, making expected counts stable.
ready=0
for attempt in $(seq 1 60); do
  counts="$(query flow_scheduler "SELECT (SELECT COUNT(*) FROM flow_schedule_trigger WHERE schedule_id='$FLOW_M2_SCHEDULE_ID'), (SELECT COUNT(*) FROM flow_schedule_outbox o JOIN flow_schedule_trigger t ON t.id=o.trigger_id WHERE t.schedule_id='$FLOW_M2_SCHEDULE_ID' AND o.delivery_state='DELIVERED')" || true)"
  read -r triggers delivered <<< "$counts"
  run_count="$(query flow_engine "SELECT COUNT(*) FROM flow_workflow_run WHERE schedule_id='$FLOW_M2_SCHEDULE_ID'" || true)"
  inbox_count="$(query flow_engine "SELECT COUNT(*) FROM flow_engine_inbox" || true)"
  if [[ "${triggers:-}" =~ ^[0-9]+$ && "$triggers" -gt 0 && "$delivered" = "$triggers" && "$run_count" = "$triggers" && "$inbox_count" -ge "$triggers" ]]; then
    ready=1; break
  fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "Engine delivery counts not matched: trigger=$triggers delivered=$delivered runs=$run_count inbox=$inbox_count"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 15 -sS -o "$WORK/runs.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" \
  "$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/runs")"
[ "$code" = 200 ] || { echo "Engine REST query expected 200, got $code"; cat "$WORK/runs.json"; exit 1; }
python3 -c 'import json,sys;rs=json.load(open(sys.argv[1])); sid=sys.argv[2]; assert any(r["scheduleId"]==sid and r["status"]=="WAITING_DISPATCH" for r in rs)' "$WORK/runs.json" "$FLOW_M2_SCHEDULE_ID"
# Simulate a lost scheduler ACK after Engine has already committed, then restart the Engine.
event="$(query flow_scheduler "SELECT o.event_id FROM flow_schedule_outbox o JOIN flow_schedule_trigger t ON t.id=o.trigger_id WHERE t.schedule_id='$FLOW_M2_SCHEDULE_ID' ORDER BY o.event_id LIMIT 1")"
[ -n "$event" ] || { echo 'No persisted Outbox event found'; exit 1; }
before="$(query flow_engine "SELECT COUNT(*) FROM flow_workflow_run WHERE schedule_id='$FLOW_M2_SCHEDULE_ID'")"
docker compose restart flow-engine
query flow_scheduler "UPDATE flow_schedule_outbox SET delivery_state='PENDING', claim_token=NULL, lease_until=NULL, next_attempt_at='1970-01-01 00:00:00.000', delivered_at=NULL WHERE event_id='$event'"
recovered=0
for attempt in $(seq 1 65); do
  status="$(query flow_scheduler "SELECT delivery_state FROM flow_schedule_outbox WHERE event_id='$event'" || true)"
  attempts="$(query flow_scheduler "SELECT attempts FROM flow_schedule_outbox WHERE event_id='$event'" || true)"
  if [ "$status" = DELIVERED ] && [[ "${attempts:-}" =~ ^[0-9]+$ ]] && [ "$attempts" -ge 2 ]; then recovered=1; break; fi
  sleep 2
done
[ "$recovered" = 1 ] || { echo "Engine failed to recover replay after restart: status=$status attempts=$attempts"; exit 1; }
after="$(query flow_engine "SELECT COUNT(*) FROM flow_workflow_run WHERE schedule_id='$FLOW_M2_SCHEDULE_ID'")"
[ "$after" = "$before" ] || { echo "Duplicate logical Run detected: before=$before after=$after"; exit 1; }
unique="$(query flow_engine "SELECT COUNT(*) FROM (SELECT schedule_id,schedule_revision,planned_fire_at_utc,origin,COUNT(*) cnt FROM flow_workflow_run WHERE schedule_id='$FLOW_M2_SCHEDULE_ID' GROUP BY schedule_id,schedule_revision,planned_fire_at_utc,origin HAVING cnt > 1) x")"
[ "$unique" = 0 ] || { echo 'Duplicate Run logical fire unique key'; exit 1; }
echo 'PASS: Engine Inbox + Run atomic persistence; Scheduler Outbox ack; idempotent replay across Engine restart'
echo 'NOTE: WAITING_DISPATCH is a durable logical run; Worker execution is not yet implemented.'
