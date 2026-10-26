#!/usr/bin/env bash
# CI-only real Compose E2E: Engine -> Dispatcher RPC, idempotency and fencing.
set -euo pipefail
: "${FLOW_M2_PROJECT_ID:?Missing project}"
: "${FLOW_M2_SCHEDULE_ID:?Missing schedule}"
: "${FLOW_DB_ROOT_PASSWORD:?Missing MySQL root password}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mysql_query() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' _ "$1" "$2"
}
probe() {
  # nginx:alpine BusyBox wget; Dispatcher HTTP has no published host port and this profile is CI-only.
  docker compose exec -T flow-web wget -qO- --post-data='' "http://flow-dispatcher:8084/_e2e/dispatch/$1?$2"
}
docker compose ps --services --status running | grep -qx flow-dispatcher
docker compose ps --services --status running | grep -qx flow-dispatcher-replica
ready=0
for i in $(seq 1 70); do
  created="$(mysql_query flow_engine "SELECT COUNT(*) FROM flow_workflow_run WHERE schedule_id='$FLOW_M2_SCHEDULE_ID'" || true)"
  dispatched="$(mysql_query flow_dispatcher "SELECT COUNT(*) FROM flow_dispatch_task WHERE schedule_id='$FLOW_M2_SCHEDULE_ID'" || true)"
  outstanding="$(mysql_query flow_engine "SELECT COUNT(*) FROM flow_engine_dispatch_outbox o JOIN flow_workflow_run r ON r.id=o.run_id WHERE r.schedule_id='$FLOW_M2_SCHEDULE_ID' AND o.delivery_state <> 'DELIVERED'" || true)"
  if [[ "${created:-}" =~ ^[0-9]+$ && "$created" -gt 0 && "$created" = "$dispatched" && "$outstanding" = 0 ]]; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "Engine to Dispatcher delivery incomplete: engine=$created dispatch=$dispatched unacked=$outstanding"; exit 1; }
# Force lost ACK: identical Engine runId must be accepted as duplicate, not a second queue record.
run_id="$(mysql_query flow_engine "SELECT id FROM flow_workflow_run WHERE schedule_id='$FLOW_M2_SCHEDULE_ID' ORDER BY created_at LIMIT 1")"
[ -n "$run_id" ] || { echo 'Engine run missing'; exit 1; }
mysql_query flow_engine "UPDATE flow_engine_dispatch_outbox SET delivery_state='PENDING', claim_token=NULL, lease_until=NULL, next_attempt_at='1970-01-01 00:00:00.000', delivered_at=NULL WHERE run_id='$run_id'"
restored=0
for i in $(seq 1 45); do
  state="$(mysql_query flow_engine "SELECT delivery_state FROM flow_engine_dispatch_outbox WHERE run_id='$run_id'" || true)"
  [ "$state" = DELIVERED ] && { restored=1; break; }
  sleep 2
done
[ "$restored" = 1 ] || { echo 'Engine dispatch ACK was not restored'; exit 1; }
after="$(mysql_query flow_dispatcher "SELECT COUNT(*) FROM flow_dispatch_task WHERE schedule_id='$FLOW_M2_SCHEDULE_ID'")"
[ "$after" = "$dispatched" ] || { echo "Duplicate dispatch tasks: before=$dispatched after=$after"; exit 1; }
# The e2e probe uses actual Dispatcher service methods and database, never production public API.
for i in $(seq 1 30); do
  if probe claim 'workerId=e2e-worker-alpha&handlerKey=safe.echo' > "$WORK/first.json" 2>/dev/null; then
    if python3 -c 'import json,sys;assert len(json.load(open(sys.argv[1]))["leases"])==1' "$WORK/first.json" 2>/dev/null; then break; fi
  fi
  sleep 1
done
read -r task epoch1 token1 < <(python3 -c 'import json,sys;x=json.load(open(sys.argv[1]))["leases"][0];print(x["taskId"],x["leaseEpoch"],x["leaseToken"])' "$WORK/first.json")
[ "$epoch1" = 1 ] || { echo 'Unexpected first epoch'; exit 1; }
mysql_query flow_dispatcher "UPDATE flow_dispatch_task SET lease_until='2000-01-01 00:00:00.000' WHERE id='$task'"
probe claim 'workerId=e2e-worker-bravo&handlerKey=safe.echo' > "$WORK/second.json"
read -r task2 epoch2 token2 < <(python3 -c 'import json,sys;x=json.load(open(sys.argv[1]))["leases"][0];print(x["taskId"],x["leaseEpoch"],x["leaseToken"])' "$WORK/second.json")
[ "$task" = "$task2" ] && [ "$epoch2" -gt "$epoch1" ] && [ "$token1" != "$token2" ] || { echo 'Fencing handoff violated'; exit 1; }
probe complete "workerId=e2e-worker-alpha&taskId=$task&leaseEpoch=$epoch1&leaseToken=$token1&outcome=SUCCEEDED" > "$WORK/stale.json"
python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["code"]=="STALE"' "$WORK/stale.json"
probe heartbeat "workerId=e2e-worker-bravo&taskId=$task&leaseEpoch=$epoch2&leaseToken=$token2" > "$WORK/heartbeat.json"
python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["code"]=="OK"' "$WORK/heartbeat.json"
probe complete "workerId=e2e-worker-bravo&taskId=$task&leaseEpoch=$epoch2&leaseToken=$token2&outcome=SUCCEEDED" > "$WORK/done.json"
python3 -c 'import json,sys;r=json.load(open(sys.argv[1]));assert r["code"]=="OK" and r["taskStatus"]=="SUCCEEDED"' "$WORK/done.json"
final="$(mysql_query flow_dispatcher "SELECT status FROM flow_dispatch_task WHERE id='$task'")"
[ "$final" = SUCCEEDED ] || { echo "Terminal state mismatch: $final"; exit 1; }
audit="$(mysql_query flow_dispatcher "SELECT GROUP_CONCAT(status ORDER BY lease_epoch) FROM flow_dispatch_attempt WHERE task_id='$task'")"
[ "$audit" = EXPIRED,SUCCEEDED ] || { echo "Attempt audit mismatch: $audit"; exit 1; }
echo 'PASS: Engine to Dispatcher RPC/outbox + unique queue + two replicas + fenced expired Worker + new epoch completion'
echo 'NOTE: test-only e2e probe; Worker SDK and Engine outcome aggregation are not implemented.'
