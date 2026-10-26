#!/usr/bin/env bash
# Only runs against real Compose: REST -> OTRYX RPC -> Scheduler -> MySQL.
set -euo pipefail
: "${FLOW_M2_COOKIE:?Session cookie required}"
: "${FLOW_M2_TOKEN:?CSRF token required}"
: "${FLOW_M2_PROJECT_ID:?Project required}"
: "${FLOW_M2_JOB_ID:?Published Job ID required}"
: "${FLOW_DB_ROOT_PASSWORD:?MySQL root password required}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
URL="$BASE/api/v1/projects/$FLOW_M2_PROJECT_ID/schedules"
KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
cat > "$WORK/schedule.json" <<JSON
{"jobId":"$FLOW_M2_JOB_ID","jobRevision":2,"expression":"*/5 * * * * *","timeZone":"Etc/UTC","misfirePolicy":"FIRE_ONCE"}
JSON
# OTRYX maintains a real asynchronous application directory; give it a bounded warmup.
for attempt in $(seq 1 35); do
  code="$(curl --connect-timeout 3 --max-time 10 -sS -o "$WORK/list.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" "$URL" || true)"
  [ "$code" = 200 ] && break
  sleep 2
done
[ "$code" = 200 ] || { echo "Scheduler RPC discovery failed: $code"; exit 1; }
code="$(curl --connect-timeout 3 --max-time 12 -sS -o "$WORK/create.json" -w '%{http_code}' \
  -b "$FLOW_M2_COOKIE" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $KEY" --data-binary @"$WORK/schedule.json" "$URL")"
[ "$code" = 201 ] || { echo "Scheduler create expected 201, got $code"; cat "$WORK/create.json"; exit 1; }
SCHEDULE_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$WORK/create.json")"
code="$(curl --max-time 12 -sS -o "$WORK/replay.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $KEY" --data-binary @"$WORK/schedule.json" "$URL")"
[ "$code" = 200 ] || { echo "Scheduler idempotent replay expected 200, got $code"; exit 1; }
python3 -c 'import json,sys;a=json.load(open(sys.argv[1]));b=json.load(open(sys.argv[2]));assert a["id"] == b["id"]' "$WORK/create.json" "$WORK/replay.json"
# Only authenticated clients may query; a mutated request using the same key conflicts.
cat > "$WORK/conflict.json" <<JSON
{"jobId":"$FLOW_M2_JOB_ID","jobRevision":2,"expression":"*/10 * * * * *","timeZone":"Etc/UTC","misfirePolicy":"SKIP"}
JSON
code="$(curl --max-time 12 -sS -o /dev/null -w '%{http_code}' -b "$FLOW_M2_COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -H "Idempotency-Key: $KEY" --data-binary @"$WORK/conflict.json" "$URL")"
[ "$code" = 409 ] || { echo "Conflicting schedule replay expected 409, got $code"; exit 1; }
# Confirm both independent scheduler processes run with the same shared DB.
docker compose ps --services --status running | grep -qx flow-scheduler
docker compose ps --services --status running | grep -qx flow-scheduler-replica
mysql_query() {
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --silent --skip-column-names -uroot flow_scheduler -e "$1"' _ "$1"
}
# Query both logical fire and Outbox counts directly in their owner Schema.
ready=0
for attempt in $(seq 1 30); do
  result="$(mysql_query "SELECT (SELECT COUNT(*) FROM flow_schedule_trigger WHERE schedule_id='$SCHEDULE_ID'), (SELECT COUNT(*) FROM flow_schedule_outbox o JOIN flow_schedule_trigger t ON t.id=o.trigger_id WHERE t.schedule_id='$SCHEDULE_ID')" || true)"
  read -r fired outbox <<< "$result"
  if [[ "${fired:-0}" =~ ^[0-9]+$ && "$fired" -gt 0 && "$fired" = "$outbox" ]]; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "No matching scheduled triggers and outbox: $result"; exit 1; }
# Index uniqueness is a DB invariant; stale/duplicate owners must not create duplicates.
duplicate="$(mysql_query "SELECT COUNT(*) FROM (SELECT schedule_id, schedule_revision, planned_fire_at_utc, origin, COUNT(*) cnt FROM flow_schedule_trigger WHERE schedule_id='$SCHEDULE_ID' GROUP BY schedule_id, schedule_revision, planned_fire_at_utc, origin HAVING cnt>1) d")"
[ "$duplicate" = 0 ] || { echo 'Duplicate logical Cron fires detected'; exit 1; }
code="$(curl --max-time 12 -sS -o "$WORK/triggers.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" "$URL/$SCHEDULE_ID/triggers")"
[ "$code" = 200 ] || { echo "Scheduler triggers expected 200, got $code"; exit 1; }
python3 -c 'import json,sys;events=json.load(open(sys.argv[1]));assert len(events)>0 and all(e["status"] in ("PENDING_ENGINE","ENGINE_ACCEPTED") for e in events)' "$WORK/triggers.json"
# Verify a scanner replica survives a peer restart and continues to create new fire rows.
before="$fired"
docker compose restart flow-scheduler
restored=0
for attempt in $(seq 1 30); do
  after="$(mysql_query "SELECT COUNT(*) FROM flow_schedule_trigger WHERE schedule_id='$SCHEDULE_ID'" || true)"
  if [[ "$after" =~ ^[0-9]+$ && "$after" -gt "$before" ]]; then restored=1; break; fi
  sleep 2
done
[ "$restored" = 1 ] || { echo 'Scheduler failed to recover from peer restart'; exit 1; }
# Toggle version is optimistic; previous version must be rejected.
code="$(curl --max-time 12 -sS -o "$WORK/latest.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" "$URL")"
[ "$code" = 200 ] || { echo "Schedule list expected 200, got $code"; exit 1; }
version="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));sid=sys.argv[2];print(next(row["version"] for row in d if row["id"]==sid))' "$WORK/latest.json" "$SCHEDULE_ID")"
code="$(curl --max-time 12 -sS -o "$WORK/paused.json" -w '%{http_code}' -b "$FLOW_M2_COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -d "{\"expectedVersion\":$version,\"enabled\":false}" "$URL/$SCHEDULE_ID/enabled")"
[ "$code" = 200 ] || { echo "Pause expected 200, got $code"; cat "$WORK/paused.json"; exit 1; }
code="$(curl --max-time 12 -sS -o /dev/null -w '%{http_code}' -b "$FLOW_M2_COOKIE" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $FLOW_M2_TOKEN" \
  -d "{\"expectedVersion\":$version,\"enabled\":true}" "$URL/$SCHEDULE_ID/enabled")"
[ "$code" = 409 ] || { echo "Stale schedule toggle expected 409, got $code"; exit 1; }
if [ "${FLOW_E2E_M2_ENGINE:-0}" = 1 ]; then
  FLOW_M2_SCHEDULE_ID="$SCHEDULE_ID" bash scripts/test_m2_engine_e2e.sh
fi
echo 'PASS: dual Scheduler replicas, unique logical fire, atomic Outbox, RPC/REST create, CAS and restart recovery'
echo 'NOTE: ENGINE_ACCEPTED is an acknowledged logical fire, not an executed Worker task.'
