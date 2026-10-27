#!/usr/bin/env bash
# Real M3 DAG integration: API -> Metadata Revision RPC -> Engine -> Dispatcher -> Worker -> Engine.
set -euo pipefail
: "${FLOW_M3_COOKIE:?Missing session cookie}"
: "${FLOW_M3_TOKEN:?Missing CSRF token}"
: "${FLOW_M3_PROJECT_ID:?Missing project UUID}"
: "${FLOW_DB_ROOT_PASSWORD:?Missing MySQL credentials}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
TMP="$(mktemp -d)"
cleanup() {
  docker rm -f flow-worker-demo-m3 >/dev/null 2>&1 || true
  docker compose --profile worker-demo stop flow-worker-demo >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
DEF="$BASE/api/v1/projects/$FLOW_M3_PROJECT_ID/workflows"
RUNS="$BASE/api/v1/projects/$FLOW_M3_PROJECT_ID/workflow-runs"
post() {
  curl --connect-timeout 3 --max-time 18 -sS -o "$4" -w '%{http_code}' \
    -c "$FLOW_M3_COOKIE" -b "$FLOW_M3_COOKIE" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M3_TOKEN" \
    -H "Idempotency-Key: $1" --data-binary "@$2" "$3"
}
put() {
  curl --connect-timeout 3 --max-time 18 -sS -o "$3" -w '%{http_code}' -X PUT \
    -c "$FLOW_M3_COOKIE" -b "$FLOW_M3_COOKIE" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M3_TOKEN" \
    --data-binary "@$1" "$2"
}
get() {
  curl --connect-timeout 3 --max-time 15 -fsS \
    -c "$FLOW_M3_COOKIE" -b "$FLOW_M3_COOKIE" "$1"
}
sql() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' _ "$1" "$2"
}
key() { python3 -c 'import uuid;print(uuid.uuid4())'; }
field() { python3 - "$1" "$2" <<'PY'
import json,sys
v=json.load(open(sys.argv[1]))
for k in sys.argv[2].split('.'): v=v[k]
print(v)
PY
}
expected() {
  [ "$1" = "$2" ] || { echo "Expected HTTP $2, got $1 at $3"; cat "$3"; exit 1; }
}
make_start() {
  python3 - "$1" "$2" "$3" "$4" <<'PY'
import json,sys
workflow,rev,input,dest=sys.argv[1:]
with open(dest,"w") as f:
    json.dump({"workflowId":workflow,"workflowRevision":int(rev),"inputJson":input},f)
PY
}
wait_run() {
  local id="$1" expected="$2" output="$3" status=""
  for round in $(seq 1 100); do
    if get "$RUNS/$id" > "$output"; then
      status="$(field "$output" run.status)"
      if [ "$status" = "$expected" ]; then return 0; fi
    fi
    sleep 2
  done
  echo "DAG run $id not terminal: observed $status expected $expected"
  cat "$output"; exit 1
}
python3 - "$TMP" <<'PY'
import json,pathlib,sys
d=pathlib.Path(sys.argv[1])
def task(i,handler="safe.echo"):
    return dict(id=i,type="TASK",handlerKey=handler,parameters={},
                timeoutSeconds=30,maxAttempts=2)
def edge(i,a,b,branch="ALWAYS"):
    return dict(id=i,source=a,target=b,branch=branch)
def dump(name,obj):
    (d/name).write_text(json.dumps(obj),encoding="utf-8")
diamond=dict(schemaVersion=1,nodes=[task(i) for i in "ABCD"],
             edges=[edge(a+b,a,b) for a,b in [("A","B"),("A","C"),("B","D"),("C","D")]])
dump("diamond.json",{"name":"DAG diamond r1","graphJson":json.dumps(diamond)})
revised=json.loads(json.dumps(diamond))
revised["nodes"][0]["handlerKey"]="safe.fail"
dump("diamond-r2.json",{"expectedDraftVersion":1,"name":"DAG diamond r2",
                        "graphJson":json.dumps(revised)})
conditional=dict(schemaVersion=1,nodes=[
    dict(id="A",type="CONDITION",condition=dict(op="equals",path="$.region",value="CN")),
    task("B"),task("C"),task("D")],
    edges=[edge("ab","A","B","TRUE"),edge("ac","A","C","FALSE"),
           edge("bd","B","D"),edge("cd","C","D")])
dump("conditional.json",{"name":"DAG condition","graphJson":json.dumps(conditional)})
failed=dict(schemaVersion=1,nodes=[task("A","safe.fail")],edges=[])
dump("fail.json",{"name":"DAG failure","graphJson":json.dumps(failed)})
dump("publish-1.json",{"expectedDraftVersion":1})
dump("publish-2.json",{"expectedDraftVersion":2})
PY

# Create and start R1 while Worker is stopped, then publish R2 before any physical execution.
code="$(post "$(key)" "$TMP/diamond.json" "$DEF" "$TMP/diamond-created.json")"
expected "$code" 201 "$TMP/diamond-created.json"
WF="$(field "$TMP/diamond-created.json" id)"
code="$(post "$(key)" "$TMP/publish-1.json" "$DEF/$WF/publish" "$TMP/diamond-published.json")"
expected "$code" 200 "$TMP/diamond-published.json"
get "$DEF/$WF/revisions/1" > "$TMP/fixed-r1.json"
make_start "$WF" 1 '{}' "$TMP/start-diamond.json"
RUN_KEY="$(key)"
code="$(post "$RUN_KEY" "$TMP/start-diamond.json" "$RUNS" "$TMP/diamond-started.json")"
expected "$code" 201 "$TMP/diamond-started.json"
RUN="$(field "$TMP/diamond-started.json" id)"
code="$(post "$RUN_KEY" "$TMP/start-diamond.json" "$RUNS" "$TMP/start-replay.json")"
expected "$code" 200 "$TMP/start-replay.json"
[ "$(field "$TMP/start-replay.json" id)" = "$RUN" ] || { echo 'Idempotency created extra DAG Run'; exit 1; }
make_start "$WF" 1 '{"different":true}' "$TMP/changed-start.json"
code="$(post "$RUN_KEY" "$TMP/changed-start.json" "$RUNS" "$TMP/start-conflict.json")"
expected "$code" 409 "$TMP/start-conflict.json"
code="$(put "$TMP/diamond-r2.json" "$DEF/$WF/draft" "$TMP/diamond-updated.json")"
expected "$code" 200 "$TMP/diamond-updated.json"
code="$(post "$(key)" "$TMP/publish-2.json" "$DEF/$WF/publish" "$TMP/diamond-published-r2.json")"
expected "$code" 200 "$TMP/diamond-published-r2.json"
get "$DEF/$WF/revisions/1" > "$TMP/fixed-r1-again.json"
python3 - "$TMP/fixed-r1.json" "$TMP/fixed-r1-again.json" "$TMP/diamond-started.json" <<'PY'
import json,sys
before,after,started=[json.load(open(p)) for p in sys.argv[1:]]
assert before==after and before["revision"]==1
assert started["graphSha256"]==before["graphSha256"]
PY

# Crash Engine while node READY is persisted. Two independently identified Worker processes resume.
docker compose restart flow-engine
docker compose --profile worker-demo up -d flow-worker-demo
docker compose --profile worker-demo run --detach --no-deps --name flow-worker-demo-m3 \
  -e FLOW_WORKER_ID=flow-worker-demo-m3 flow-worker-demo >/dev/null
wait_run "$RUN" SUCCEEDED "$TMP/diamond-finished.json"
python3 - "$TMP/diamond-finished.json" <<'PY'
import json,sys
run=json.load(open(sys.argv[1])); nodes={n["nodeId"]:n for n in run["nodes"]}
assert set(nodes)==set("ABCD") and all(n["status"]=="SUCCEEDED" for n in nodes.values())
PY
NODE_D="$(python3 -c 'import json,sys;print(next(x["id"] for x in json.load(open(sys.argv[1]))["nodes"] if x["nodeId"]=="D"))' "$TMP/diamond-finished.json")"
COUNT="$(sql flow_dispatcher "SELECT COUNT(*) FROM flow_dispatch_task WHERE engine_run_id='$NODE_D'")"
[ "$COUNT" = 1 ] || { echo "Diamond join D created $COUNT Dispatcher tasks"; exit 1; }
COUNT="$(sql flow_engine "SELECT COUNT(*) FROM flow_dag_outcome_inbox i JOIN flow_dag_node_run n ON n.id=i.node_run_id WHERE n.dag_run_id='$RUN'")"
[ "$COUNT" = 4 ] || { echo "Expected 4 distinct DAG node outcomes, got $COUNT"; exit 1; }
ORDER_OK="$(sql flow_engine "SELECT CASE WHEN (SELECT o.created_at FROM flow_dag_dispatch_outbox o JOIN flow_dag_node_run n ON n.id=o.node_run_id WHERE n.dag_run_id='$RUN' AND n.node_id='D') >= (SELECT MAX(n.updated_at) FROM flow_dag_node_run n WHERE n.dag_run_id='$RUN' AND n.node_id IN ('B','C')) THEN 'YES' ELSE 'NO' END")"
[ "$ORDER_OK" = YES ] || { echo 'Join D dispatched before B/C completed'; exit 1; }

# Loss of Dispatcher ACK replays identical terminal event into node-scoped idempotent Inbox.
sql flow_dispatcher "UPDATE flow_dispatch_outcome_outbox SET delivery_state='PENDING', claim_token=NULL,lease_until=NULL,next_attempt_at='1970-01-01 00:00:00.000', delivered_at=NULL WHERE engine_run_id='$NODE_D'"
recovered=0
for i in $(seq 1 45); do
  STATUS="$(sql flow_dispatcher "SELECT delivery_state FROM flow_dispatch_outcome_outbox WHERE engine_run_id='$NODE_D'")"
  if [ "$STATUS" = DELIVERED ]; then recovered=1; break; fi
  sleep 2
done
[ "$recovered" = 1 ] || { echo 'Node terminal ACK replay did not recover'; exit 1; }
COUNT="$(sql flow_engine "SELECT COUNT(*) FROM flow_dag_outcome_inbox WHERE node_run_id='$NODE_D'")"
[ "$COUNT" = 1 ] || { echo 'Replay duplicated Engine Node Inbox'; exit 1; }

# CONDITION chooses one branch, the opposite branch remains SKIPPED.
code="$(post "$(key)" "$TMP/conditional.json" "$DEF" "$TMP/conditional-created.json")"
expected "$code" 201 "$TMP/conditional-created.json"
CW="$(field "$TMP/conditional-created.json" id)"
code="$(post "$(key)" "$TMP/publish-1.json" "$DEF/$CW/publish" "$TMP/conditional-published.json")"
expected "$code" 200 "$TMP/conditional-published.json"
for region in CN US; do
  make_start "$CW" 1 "{\"region\":\"$region\"}" "$TMP/start-$region.json"
  code="$(post "$(key)" "$TMP/start-$region.json" "$RUNS" "$TMP/started-$region.json")"
  expected "$code" 201 "$TMP/started-$region.json"
  ID="$(field "$TMP/started-$region.json" id)"
  wait_run "$ID" SUCCEEDED "$TMP/finished-$region.json"
  python3 - "$TMP/finished-$region.json" "$region" <<'PY'
import json,sys
run=json.load(open(sys.argv[1]));region=sys.argv[2]
n={x["nodeId"]:x for x in run["nodes"]}
assert n["A"]["status"]=="SUCCEEDED" and n["A"]["conditionDecision"]==(region=="CN")
assert n["B"]["status"]==("SUCCEEDED" if region=="CN" else "SKIPPED")
assert n["C"]["status"]==("SKIPPED" if region=="CN" else "SUCCEEDED")
assert n["D"]["status"]=="SUCCEEDED"
PY
done

# Bounded Worker retry of trusted failing Handler settles DAG as FAILED.
code="$(post "$(key)" "$TMP/fail.json" "$DEF" "$TMP/fail-created.json")"
expected "$code" 201 "$TMP/fail-created.json"
FW="$(field "$TMP/fail-created.json" id)"
code="$(post "$(key)" "$TMP/publish-1.json" "$DEF/$FW/publish" "$TMP/fail-published.json")"
expected "$code" 200 "$TMP/fail-published.json"
make_start "$FW" 1 '{}' "$TMP/fail-start.json"
code="$(post "$(key)" "$TMP/fail-start.json" "$RUNS" "$TMP/fail-started.json")"
expected "$code" 201 "$TMP/fail-started.json"
FR="$(field "$TMP/fail-started.json" id)"
wait_run "$FR" FAILED "$TMP/fail-finished.json"
FN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["nodes"][0]["id"])' "$TMP/fail-finished.json")"
ATTEMPTS="$(sql flow_dispatcher "SELECT COUNT(*) FROM flow_dispatch_attempt a JOIN flow_dispatch_task t ON t.id=a.task_id WHERE t.engine_run_id='$FN'")"
[ "$ATTEMPTS" = 2 ] || { echo "Failure expected 2 Worker attempts, got $ATTEMPTS"; exit 1; }
COUNT="$(sql flow_engine "SELECT COUNT(*) FROM flow_dag_outcome_inbox WHERE node_run_id='$FN' AND outcome='FAILED'")"
[ "$COUNT" = 1 ] || { echo 'Failure Engine outcome missing or duplicated'; exit 1; }

code="$(curl --connect-timeout 3 --max-time 15 -sS -o /dev/null -w '%{http_code}' "$RUNS")"
[ "$code" = 401 ] || { echo "Unauthenticated DAG run list expected 401, got $code"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 15 -sS -o /dev/null -w '%{http_code}' -b "$FLOW_M3_COOKIE" -H 'Content-Type: application/json' -H 'X-XSRF-TOKEN: invalid' -H "Idempotency-Key: $(key)" --data-binary "@$TMP/start-diamond.json" "$RUNS")"
[ "$code" = 403 ] || { echo "Invalid CSRF expected 403, got $code"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 15 -sS -o /dev/null -w '%{http_code}' -b "$FLOW_M3_COOKIE" "$BASE/api/v1/projects/00000000-0000-4000-8000-000000000099/workflow-runs/$RUN")"
[ "$code" = 404 ] || { echo "Foreign project DAG read expected 404, got $code"; exit 1; }
echo 'PASS: FLOW-016 real Worker DAG parallel diamond, branch SKIPPED, failure retries, ACK replay, immutable R1, Engine restart and session boundaries'
