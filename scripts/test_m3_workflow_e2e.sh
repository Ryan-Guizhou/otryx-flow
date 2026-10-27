#!/usr/bin/env bash
# FLOW-015 real REST -> OTRYX RPC -> MySQL acceptance; isolated Compose only.
set -euo pipefail
: "$FLOW_M3_COOKIE"; : "$FLOW_M3_TOKEN"; : "$FLOW_M3_PROJECT_ID"; : "$FLOW_DB_ROOT_PASSWORD"
BASE="http://127.0.0.1:8088"
if [ -n "$(printenv FLOW_BASE_URL || true)" ]; then BASE="$FLOW_BASE_URL"; fi
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
URL="$BASE/api/v1/projects/$FLOW_M3_PROJECT_ID/workflows"
KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
PUB="$(python3 -c 'import uuid;print(uuid.uuid4())')"
python3 - "$TMP" <<'PY'
import json,pathlib,sys
d=pathlib.Path(sys.argv[1])
nodes=[{"id":k,"type":"TASK","handlerKey":"safe.echo","parameters":{},
        "timeoutSeconds":30,"maxAttempts":2} for k in "ABCD"]
edges=[{"id":a+b,"source":a,"target":b,"branch":"ALWAYS"} for a,b
       in [("A","B"),("A","C"),("B","D"),("C","D")]]
graph={"schemaVersion":1,"nodes":nodes,"edges":edges}
def write(file,obj): (d/file).write_text(json.dumps(obj),encoding="utf-8")
write("draft.json",{"name":"M3 diamond","graphJson":json.dumps(graph)})
write("conflict.json",{"name":"Different name","graphJson":json.dumps(graph)})
cycle=json.loads(json.dumps(graph))
cycle["edges"].append({"id":"DA","source":"D","target":"A","branch":"ALWAYS"})
write("cycle.json",{"name":"Cyclic","graphJson":json.dumps(cycle)})
unsafe=json.loads(json.dumps(graph));unsafe["nodes"][0]["handlerKey"]="java.lang.Runtime.exec"
write("unsafe.json",{"name":"Unsafe","graphJson":json.dumps(unsafe)})
modified=json.loads(json.dumps(graph));modified["nodes"][0]["handlerKey"]="safe.fail"
write("updated.json",{"name":"M3 revised","expectedDraftVersion":1,"graphJson":json.dumps(modified)})
write("r1.json",{"expectedDraftVersion":1})
write("r2.json",{"expectedDraftVersion":2})
PY
post() {
  curl --connect-timeout 3 --max-time 15 -sS -o "$4" -w '%{http_code}' \
    -c "$FLOW_M3_COOKIE" -b "$FLOW_M3_COOKIE" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M3_TOKEN" \
    -H "Idempotency-Key: $1" --data-binary "@$2" "$3"
}
put() {
  curl --connect-timeout 3 --max-time 15 -sS -o "$3" -w '%{http_code}' \
    -c "$FLOW_M3_COOKIE" -b "$FLOW_M3_COOKIE" -X PUT \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M3_TOKEN" \
    --data-binary "@$1" "$2"
}
get() { curl --connect-timeout 3 --max-time 15 -fsS -c "$FLOW_M3_COOKIE" -b "$FLOW_M3_COOKIE" "$1"; }
sql() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot "$1" -e "$2"' _ "$1" "$2"
}
code="$(post "$KEY" "$TMP/draft.json" "$URL" "$TMP/created.json")"
[ "$code" = 201 ] || { echo "Create expected 201, got $code"; cat "$TMP/created.json"; exit 1; }
ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$TMP/created.json")"
code="$(post "$KEY" "$TMP/draft.json" "$URL" "$TMP/replay.json")"
[ "$code" = 200 ] || { echo "Idempotent replay expected 200, got $code"; exit 1; }
python3 - "$TMP/created.json" "$TMP/replay.json" <<'PY'
import json,sys
a,b=[json.load(open(p)) for p in sys.argv[1:]]
assert a["id"]==b["id"] and a["draftVersion"]==1
PY
code="$(post "$KEY" "$TMP/conflict.json" "$URL" "$TMP/conflict-out.json")"
[ "$code" = 409 ] || { echo "Changed replay expected 409, got $code"; exit 1; }
code="$(post "$(python3 -c 'import uuid;print(uuid.uuid4())')" "$TMP/cycle.json" "$URL" "$TMP/cycle-out.json")"
[ "$code" = 400 ] || { echo "Cycle expected 400, got $code"; cat "$TMP/cycle-out.json"; exit 1; }
code="$(post "$(python3 -c 'import uuid;print(uuid.uuid4())')" "$TMP/unsafe.json" "$URL" "$TMP/unsafe-out.json")"
[ "$code" = 400 ] || { echo "Untrusted handler expected 400, got $code"; exit 1; }
code="$(post "$PUB" "$TMP/r1.json" "$URL/$ID/publish" "$TMP/published1.json")"
[ "$code" = 200 ] || { echo "Publish r1 expected 200, got $code"; exit 1; }
code="$(post "$PUB" "$TMP/r1.json" "$URL/$ID/publish" "$TMP/publish-replay.json")"
[ "$code" = 200 ] || { echo "Publish replay expected 200, got $code"; exit 1; }
code="$(post "$PUB" "$TMP/r2.json" "$URL/$ID/publish" "$TMP/publish-conflict.json")"
[ "$code" = 409 ] || { echo "Changed publish replay expected 409, got $code"; exit 1; }
get "$URL/$ID/revisions/1" > "$TMP/old.json"
code="$(put "$TMP/updated.json" "$URL/$ID/draft" "$TMP/saved.json")"
[ "$code" = 200 ] || { echo "Save r2 expected 200, got $code"; exit 1; }
code="$(put "$TMP/updated.json" "$URL/$ID/draft" "$TMP/stale.json")"
[ "$code" = 409 ] || { echo "Stale CAS expected 409, got $code"; exit 1; }
code="$(post "$(python3 -c 'import uuid;print(uuid.uuid4())')" "$TMP/r2.json" "$URL/$ID/publish" "$TMP/published2.json")"
[ "$code" = 200 ] || { echo "Publish r2 expected 200, got $code"; exit 1; }
get "$URL/$ID/revisions/1" > "$TMP/still-old.json"
get "$URL/$ID/revisions/2" > "$TMP/new.json"
python3 - "$TMP/old.json" "$TMP/still-old.json" "$TMP/new.json" <<'PY'
import hashlib,json,sys
old,still,new=[json.load(open(p)) for p in sys.argv[1:]]
assert old==still and old["revision"]==1 and new["revision"]==2
assert old["name"]=="M3 diamond" and new["name"]=="M3 revised"
assert "safe.echo" in old["graphJson"] and "safe.fail" in new["graphJson"]
for rev in (old,new):
    assert hashlib.sha256(rev["graphJson"].encode()).hexdigest()==rev["graphSha256"]
assert old["graphSha256"]!=new["graphSha256"]
PY
count="$(sql flow_metadata "SELECT COUNT(*) FROM flow_workflow_revision WHERE workflow_id='$ID'")"
[ "$count" = 2 ] || { echo "Expected two immutable DB revisions, got $count"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 12 -sS -o /dev/null -w '%{http_code}' \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -b "$FLOW_M3_COOKIE" -H 'X-XSRF-TOKEN: invalid' \
  --data-binary "@$TMP/draft.json" "$URL")"
[ "$code" = 403 ] || { echo "CSRF expected 403, got $code"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 12 -sS -o /dev/null -w '%{http_code}' "$URL")"
[ "$code" = 401 ] || { echo "Missing session expected 401, got $code"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 12 -sS -o /dev/null -w '%{http_code}' \
  -b "$FLOW_M3_COOKIE" "$BASE/api/v1/projects/00000000-0000-4000-8000-000000000099/workflows/$ID/revisions/1")"
[ "$code" = 404 ] || { echo "Wrong project expected 404, got $code"; exit 1; }
if [ "$(printenv FLOW_E2E_RESTART_METADATA || true)" = "1" ]; then
  docker compose restart flow-metadata
  restored=0
  for i in $(seq 1 45); do
    code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$TMP/recovered.json" -w '%{http_code}' \
      -c "$FLOW_M3_COOKIE" -b "$FLOW_M3_COOKIE" "$URL/$ID/revisions/2" || true)"
    if [ "$code" = 200 ] && python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["revision"]==2' "$TMP/recovered.json" 2>/dev/null; then
      restored=1; break
    fi
    sleep 2
  done
  [ "$restored" = 1 ] || { echo 'Immutable revision not recovered after restart'; exit 1; }
fi
echo 'PASS: FLOW-015 real Nacos RPC + MySQL draft/CAS/immutable SHA-256/security/restart'

