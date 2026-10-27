#!/usr/bin/env bash
# FLOW-017: real Compose cancellation fencing, final-state race and logical retry.
set -euo pipefail
: "${FLOW_M3_COOKIE:?Need session}"
: "${FLOW_M3_TOKEN:?Need CSRF}"
: "${FLOW_M3_PROJECT_ID:?Need project}"
: "${FLOW_DB_ROOT_PASSWORD:?Need database password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
TMP="$(mktemp -d)"
cleanup() {
  docker compose --profile worker-demo stop flow-worker-demo >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
DEF="$BASE/api/v1/projects/$FLOW_M3_PROJECT_ID/workflows"
RUNS="$BASE/api/v1/projects/$FLOW_M3_PROJECT_ID/workflow-runs"
key() { python3 -c 'import uuid;print(uuid.uuid4())'; }
field() { python3 - "$1" "$2" <<'PY'
import json,sys
v=json.load(open(sys.argv[1]))
for x in sys.argv[2].split('.'): v=v[x]
print(v)
PY
}
sql() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' _ "$1" "$2"
}
post() {
  # After fault-injected Engine restart the Nacos RPC client can briefly return 503.
  # Reuse the EXACT same idempotency key and body; do not create a second DAG Run.
  local code="000"
  for attempt in $(seq 1 16); do
    code="$(curl --connect-timeout 3 --max-time 18 -sS -o "$4" -w '%{http_code}' \
      -b "$FLOW_M3_COOKIE" -c "$FLOW_M3_COOKIE" \
      -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M3_TOKEN" \
      -H "Idempotency-Key: $1" --data-binary "@$2" "$3")" || code="000"
    case "$code" in
      000|502|503|504)
        if [ "$attempt" -lt 16 ]; then sleep 2; continue; fi
        ;;
    esac
    printf '%s' "$code"
    return 0
  done
  printf '%s' "$code"
}
get() {
  curl --connect-timeout 3 --max-time 18 -fsS -b "$FLOW_M3_COOKIE" "$1"
}
expected() {
  # 200 after a transient 503 is a valid idempotent replay of an already-committed 201.
  if [ "$2" = 201 ] && [ "$1" = 200 ]; then return 0; fi
  [ "$1" = "$2" ] || { echo "Expected HTTP $2, got $1"; cat "$3"; exit 1; }
}
probe() {
  docker compose exec -T flow-web wget -qO- --post-data='' \
    "http://flow-dispatcher:8084/_e2e/dispatch/$1?$2"
}
wait_run() {
  local id="$1" desired="$2" path="$3" state=""
  for i in $(seq 1 70); do
    if get "$RUNS/$id" > "$path"; then
      state="$(field "$path" run.status)"
      [ "$state" = "$desired" ] && return 0
    fi
    sleep 2
  done
  echo "DAG Run $id expected $desired, got $state"; cat "$path"; exit 1
}
wait_task() {
  local node="$1" state=""
  for i in $(seq 1 50); do
    state="$(sql flow_dispatcher "SELECT status FROM flow_dispatch_task WHERE engine_run_id='$node'" || true)"
    [ "$state" = READY ] && return 0
    sleep 1
  done
  echo "Dispatcher task $node not READY: $state"; exit 1
}
python3 - "$TMP" <<'PY'
import json,sys,pathlib
d=pathlib.Path(sys.argv[1])
def node(i,handler="safe.echo"):
    return {"id":i,"type":"TASK","handlerKey":handler,"parameters":{},
            "timeoutSeconds":30,"maxAttempts":2}
def link(a,b):
    return {"id":a+b,"source":a,"target":b,"branch":"ALWAYS"}
def write(file,payload): (d/file).write_text(json.dumps(payload),encoding="utf-8")
graphs={
    "cancel": {"schemaVersion":1,"nodes":[node("A"),node("B")],"edges":[link("A","B")]},
    "success": {"schemaVersion":1,"nodes":[node("A","safe.fail")],"edges":[]},
    "fail": {"schemaVersion":1,"nodes":[node("A","safe.fail")],"edges":[]}
}
for name,graph in graphs.items():
    write(name+".json",{"name":"FLOW-017 "+name,"graphJson":json.dumps(graph)})
write("publish.json",{"expectedDraftVersion":1})
PY
start_workflow() {
  local graph="$1" create="$TMP/$1-created.json" id pub="$TMP/$1-published.json"
  local code
  code="$(post "$(key)" "$TMP/$graph.json" "$DEF" "$create")"
  expected "$code" 201 "$create"
  id="$(field "$create" id)"
  code="$(post "$(key)" "$TMP/publish.json" "$DEF/$id/publish" "$pub")"
  expected "$code" 200 "$pub"
  python3 - "$id" "$TMP/$graph-start.json" <<'PY'
import json,sys
json.dump({"workflowId":sys.argv[1],"workflowRevision":1,"inputJson":"{}"},open(sys.argv[2],"w"))
PY
  code="$(post "$(key)" "$TMP/$graph-start.json" "$RUNS" "$TMP/$graph-started.json")"
  expected "$code" 201 "$TMP/$graph-started.json"
}
cancel_run() {
  local run="$1" version="$2" key="$3" file="$4"
  printf '{"expectedRunVersion":%s}' "$version" > "$TMP/cancel-body.json"
  post "$key" "$TMP/cancel-body.json" "$RUNS/$run/cancel" "$file"
}
# No Worker is running: zero physical attempts, cancellation fenced before/after enqueue race.
docker compose --profile worker-demo stop flow-worker-demo >/dev/null 2>&1 || true
start_workflow cancel
RUN="$(field "$TMP/cancel-started.json" id)"
VERSION="$(field "$TMP/cancel-started.json" runVersion)"
CANCEL_KEY="$(key)"
code="$(cancel_run "$RUN" "$VERSION" "$CANCEL_KEY" "$TMP/cancel-accepted.json")"
expected "$code" 200 "$TMP/cancel-accepted.json"
code="$(cancel_run "$RUN" "$VERSION" "$CANCEL_KEY" "$TMP/cancel-replay.json")"
expected "$code" 200 "$TMP/cancel-replay.json"
python3 - "$TMP/cancel-replay.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))["duplicate"] is True
PY
code="$(cancel_run "$RUN" "$VERSION" "$(key)" "$TMP/cancel-stale.json")"
expected "$code" 409 "$TMP/cancel-stale.json"
wait_run "$RUN" CANCELLED "$TMP/cancel-done.json"
python3 - "$TMP/cancel-done.json" <<'PY'
import json,sys
data=json.load(open(sys.argv[1]))
nodes={n["nodeId"]:n for n in data["nodes"]}
assert nodes["A"]["status"]=="CANCELLED" and nodes["B"]["status"]=="CANCELLED"
PY
NODE="$(python3 -c 'import json,sys;print(next(n["id"] for n in json.load(open(sys.argv[1]))["nodes"] if n["nodeId"]=="A"))' "$TMP/cancel-done.json")"
COUNT="$(sql flow_dispatcher "SELECT COALESCE(MAX(attempts),0) FROM flow_dispatch_task WHERE engine_run_id='$NODE'")"
[ "$COUNT" = 0 ] || { echo "Cancelled task executed: attempts=$COUNT"; exit 1; }
COUNT="$(sql flow_engine "SELECT COUNT(*) FROM flow_dag_dispatch_outbox WHERE node_run_id='$NODE' AND delivery_state IN ('CANCELLED','DELIVERED','IN_FLIGHT')")"
[ "$COUNT" = 1 ] || { echo 'Expected original dispatch envelope to be retained for audit'; exit 1; }
get "$RUNS/$RUN/history" > "$TMP/history.json"
python3 - "$TMP/history.json" <<'PY'
import json,sys
v=json.load(open(sys.argv[1]))
assert len(v)==1 and v[0]["action"]=="CANCEL"
assert v[0]["newVersion"]>v[0]["oldVersion"]
PY

# Re-deliver a committed cancellation after Engine restart: same tombstone, no new task.
sql flow_engine "UPDATE flow_dag_cancel_outbox SET delivery_state='PENDING',claim_token=NULL,lease_until=NULL,next_attempt_at='1970-01-01 00:00:00.000',delivered_at=NULL WHERE node_run_id='$NODE'"
docker compose restart flow-engine
DELIVERED=0
for i in $(seq 1 45); do
  S="$(sql flow_engine "SELECT delivery_state FROM flow_dag_cancel_outbox WHERE node_run_id='$NODE'" || true)"
  if [ "$S" = DELIVERED ]; then DELIVERED=1; break; fi
  sleep 2
done
[ "$DELIVERED" = 1 ] || { echo 'Engine cancel outbox lost after restart'; exit 1; }
COUNT="$(sql flow_dispatcher "SELECT COUNT(*) FROM flow_dispatch_task WHERE engine_run_id='$NODE'")"
[ "$COUNT" = 1 ] || { echo "Cancel replay created $COUNT physical tasks"; exit 1; }

# Worker lease is real but controlled via CI-only Dispatcher probe; cancellation must fence epoch.
start_workflow success
RUN2="$(field "$TMP/success-started.json" id)"
VERSION2="$(field "$TMP/success-started.json" runVersion)"
get "$RUNS/$RUN2" > "$TMP/success-detail.json"
NODE2="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["nodes"][0]["id"])' "$TMP/success-detail.json")"
wait_task "$NODE2"
# Use safe.fail for the fenced probe to isolate it from recurring M2 safe.echo Cron tasks.
probe claim "workerId=flow-control-probe&handlerKey=safe.fail&engineRunId=$NODE2" > "$TMP/lease.json"
read -r TASK EPOCH TOKEN CLAIM_RUN < <(python3 - "$TMP/lease.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))["leases"]
assert len(r)==1
print(r[0]["taskId"],r[0]["leaseEpoch"],r[0]["leaseToken"],r[0]["runId"])
PY
)
[ "$CLAIM_RUN" = "$NODE2" ] || { echo "CI probe claimed unrelated NodeRun: $CLAIM_RUN != $NODE2"; exit 1; }
code="$(cancel_run "$RUN2" "$VERSION2" "$(key)" "$TMP/lease-cancel.json")"
expected "$code" 200 "$TMP/lease-cancel.json"
wait_run "$RUN2" CANCELLED "$TMP/lease-cancel-done.json"
probe heartbeat "workerId=flow-control-probe&taskId=$TASK&leaseEpoch=$EPOCH&leaseToken=$TOKEN" > "$TMP/stale-heartbeat.json"
probe complete "workerId=flow-control-probe&taskId=$TASK&leaseEpoch=$EPOCH&leaseToken=$TOKEN&outcome=SUCCEEDED" > "$TMP/stale-complete.json"
python3 - "$TMP/stale-heartbeat.json" "$TMP/stale-complete.json" <<'PY'
import json,sys
assert all(json.load(open(p))["code"]=="STALE" for p in sys.argv[1:])
PY

# Real Worker does bounded internal retries; manual logical retry creates a different NodeRun ID.
start_workflow fail
RUN3="$(field "$TMP/fail-started.json" id)"
docker compose --profile worker-demo up -d flow-worker-demo
wait_run "$RUN3" FAILED "$TMP/fail-done.json"
OLD="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["nodes"][0]["id"])' "$TMP/fail-done.json")"
VERSION3="$(field "$TMP/fail-done.json" run.runVersion)"
sleep 2
printf '{"expectedRunVersion":%s}' "$VERSION3" > "$TMP/retry-body.json"
RETRY_KEY="$(key)"
code="$(post "$RETRY_KEY" "$TMP/retry-body.json" "$RUNS/$RUN3/nodes/A/retry" "$TMP/retried.json")"
expected "$code" 200 "$TMP/retried.json"
NEW="$(field "$TMP/retried.json" nodeRunId)"
[ "$NEW" != "$OLD" ] || { echo "Logical retry reused old NodeRun ID"; exit 1; }
code="$(post "$RETRY_KEY" "$TMP/retry-body.json" "$RUNS/$RUN3/nodes/A/retry" "$TMP/retry-replay.json")"
expected "$code" 200 "$TMP/retry-replay.json"
[ "$(field "$TMP/retry-replay.json" nodeRunId)" = "$NEW" ] || { echo 'Logical retry ACK replay duplicated NodeRun'; exit 1; }
wait_run "$RUN3" FAILED "$TMP/fail-again.json"
python3 - "$TMP/fail-again.json" "$OLD" "$NEW" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
nodes=r["nodes"]
assert len(nodes)==2 and {n["id"] for n in nodes}=={sys.argv[2],sys.argv[3]}
assert [n["logicalAttempt"] for n in nodes]==[1,2]
assert all(n["status"]=="FAILED" for n in nodes)
PY
COUNT="$(sql flow_engine "SELECT COUNT(*) FROM flow_dag_dispatch_outbox o JOIN flow_dag_node_run n ON n.id=o.node_run_id WHERE n.dag_run_id='$RUN3'")"
[ "$COUNT" = 2 ] || { echo "Logical retry expected two separate Node Outboxes: $COUNT"; exit 1; }
COUNT="$(sql flow_engine "SELECT COUNT(*) FROM flow_dag_outcome_inbox WHERE node_run_id IN ('$OLD','$NEW')")"
[ "$COUNT" = 2 ] || { echo "Logical retry lost or duplicated physical outcomes: $COUNT"; exit 1; }
get "$RUNS/$RUN3/history" > "$TMP/retry-history.json"
python3 - "$TMP/retry-history.json" "$NEW" <<'PY'
import json,sys
events=json.load(open(sys.argv[1]))
assert len(events)==1 and events[0]["action"]=="RETRY" and events[0]["nodeRunId"]==sys.argv[2]
PY

# Anonymous, forgery and foreign project API boundaries.
STATUS="$(curl -sS -o /dev/null -w '%{http_code}' "$RUNS/$RUN3/history")"
[ "$STATUS" = 401 ] || { echo "Anonymous audit read: $STATUS"; exit 1; }
STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -b "$FLOW_M3_COOKIE" \
  -H 'Content-Type: application/json' -H 'X-XSRF-TOKEN: invalid' \
  -H "Idempotency-Key: $(key)" --data-binary "@$TMP/retry-body.json" "$RUNS/$RUN3/cancel")"
[ "$STATUS" = 403 ] || { echo "Forged CSRF cancel: $STATUS"; exit 1; }
STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -b "$FLOW_M3_COOKIE" \
  "$BASE/api/v1/projects/00000000-0000-4000-8000-000000000099/workflow-runs/$RUN3/history")"
[ "$STATUS" = 404 ] || { echo "Cross-project audit read: $STATUS"; exit 1; }
echo 'PASS: FLOW-017 real cancel fence, in-flight lease STALE, control audit/idempotency, restart recovery, logical NodeRun retry and security'
