#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_ROOT="${FLOW_BACKUP_DIR:-$ROOT/backups}"
MYSQL_SCHEMAS=(flow_identity flow_metadata flow_scheduler flow_engine flow_dispatcher)
WRITER_SERVICES=(flow-web flow-api flow-metadata flow-scheduler flow-scheduler-replica
  flow-engine flow-dispatcher flow-dispatcher-replica flow-worker-demo)
MYSQL_DUMP_PREFIX='flow-mysql-'
TEMP_DIR=''

cleanup() {
  if [[ -n "$TEMP_DIR" ]]; then
    if [[ -d "$TEMP_DIR" ]]; then rm -rf -- "$TEMP_DIR"; else rm -f -- "$TEMP_DIR"; fi
  fi
}
trap cleanup EXIT

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: ./scripts/flow-ops.sh <command> [arguments]

Commands:
  backup                         Create a compressed, checksummed MySQL snapshot
  verify <backup-directory>     Verify manifest, checksum, and gzip integrity
  restore <directory> --confirm-overwrite
                                 Stop writers, snapshot current data, then restore
  reconcile [report-file]        Write a read-only cross-schema consistency report

Set FLOW_BACKUP_DIR to store backups outside the repository. Restore leaves
application services stopped; review the reconciliation report before startup.
USAGE
}

require_docker() {
  command -v docker >/dev/null 2>&1 || fail 'Docker Engine with Compose V2 is required.'
  docker compose version >/dev/null 2>&1 || fail 'Docker Compose V2 is required.'
}

safe_dump_name() {
  python3 - "$1" <<'PY'
import json
import re
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
name = manifest.get("dump_file")
schemas = manifest.get("schemas")
expected = ["flow_identity", "flow_metadata", "flow_scheduler", "flow_engine", "flow_dispatcher"]
if schemas != expected or not isinstance(name, str) or not re.fullmatch(r"flow-mysql-[0-9TZ-]+\.sql\.gz", name):
    raise SystemExit("Invalid backup manifest schema list or dump filename")
if manifest.get("format") != "mysqldump-gzip-v1":
    raise SystemExit("Unsupported backup format")
print(name)
PY
}

validate_dump_schemas() {
  python3 - "$1" <<'PY'
import gzip
import re
import sys

expected = {"flow_identity", "flow_metadata", "flow_scheduler", "flow_engine", "flow_dispatcher"}
observed = set()
with gzip.open(sys.argv[1], "rt", encoding="utf-8", errors="strict") as dump:
    for line in dump:
        match = re.match(r"^USE `([^`]+)`;\s*$", line)
        if match:
            observed.add(match.group(1))
if observed != expected:
    raise SystemExit("Dump does not contain exactly the five Flow schemas")
PY
}

verify_backup() {
  local directory="$1" manifest="$1/manifest.json" checksum="$1/SHA256SUMS" dump_name dump_file expected actual
  [[ -d "$directory" && ! -L "$directory" ]] || fail 'Backup path must be a real directory.'
  [[ -f "$manifest" && ! -L "$manifest" ]] || fail 'Backup manifest is missing or unsafe.'
  [[ -f "$checksum" && ! -L "$checksum" ]] || fail 'Backup checksum file is missing or unsafe.'
  dump_name="$(safe_dump_name "$manifest")" || fail 'Backup manifest validation failed.'
  dump_file="$directory/$dump_name"
  [[ -f "$dump_file" && ! -L "$dump_file" ]] || fail 'Backup dump is missing or unsafe.'
  expected="$(python3 - "$manifest" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source).get("sha256", ""))
PY
)"
  [[ "$expected" =~ ^[a-f0-9]{64}$ ]] || fail 'Backup manifest SHA-256 is malformed.'
  actual="$(sha256sum "$dump_file" | awk '{print $1}')"
  [[ "$actual" = "$expected" ]] || fail 'Backup dump checksum does not match the manifest.'
  [[ "$(cat "$checksum")" = "$expected  $dump_name" ]] || fail 'Checksum file does not match the manifest.'
  gzip -t -- "$dump_file" || fail 'Backup gzip stream is corrupt.'
  validate_dump_schemas "$dump_file" || fail 'Backup dump schema list does not match the Flow stack.'
  printf 'PASS: verified %s (sha256=%s)\n' "$dump_name" "$actual"
}

create_backup() {
  local destination="$1" stamp backup_id staging target created_at git_sha dump_sha
  mkdir -p -- "$destination"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_id="flow-$stamp-$$"
  local dump_name="${MYSQL_DUMP_PREFIX}${stamp}-$$.sql.gz"
  target="$destination/$backup_id"
  [[ ! -e "$target" ]] || fail "Backup target already exists: $target"
  staging="$(mktemp -d "$destination/.flow-backup.XXXXXX")"
  TEMP_DIR="$staging"
  chmod 700 "$staging"

  if ! docker compose exec -T mysql sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --quick --routines --events --triggers --hex-blob --databases flow_identity flow_metadata flow_scheduler flow_engine flow_dispatcher' \
      | gzip -c > "$staging/$dump_name"; then
    fail 'MySQL dump failed; incomplete backup was discarded.'
  fi
  [[ -s "$staging/$dump_name" ]] || fail 'MySQL dump is empty.'
  gzip -t -- "$staging/$dump_name" || fail 'MySQL dump gzip verification failed.'

  dump_sha="$(sha256sum "$staging/$dump_name" | awk '{print $1}')"
  created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  git_sha="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || printf 'unknown')"
  python3 - "$staging/manifest.json" "$backup_id" "$created_at" "$git_sha" "$dump_sha" "$dump_name" <<'PY'
import json
import sys

manifest = {
    "backup_id": sys.argv[2],
    "created_at_utc": sys.argv[3],
    "format": "mysqldump-gzip-v1",
    "git_sha": sys.argv[4],
    "dump_file": sys.argv[6],
    "sha256": sys.argv[5],
    "schemas": ["flow_identity", "flow_metadata", "flow_scheduler", "flow_engine", "flow_dispatcher"],
}
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(manifest, output, ensure_ascii=False, indent=2)
    output.write("\n")
PY
  printf '%s  %s\n' "$dump_sha" "$dump_name" > "$staging/SHA256SUMS"
  chmod 600 "$staging/$dump_name" "$staging/manifest.json" "$staging/SHA256SUMS"
  mv -- "$staging" "$target"
  TEMP_DIR=''
  printf 'Backup: %s\n' "$target"
}

restore_backup() {
  local directory="$1" confirmation="${2:-}" manifest="$1/manifest.json" dump_name dump_file snapshot
  [[ "$confirmation" = '--confirm-overwrite' ]] || fail 'Restore overwrites the five Flow schemas. Repeat with --confirm-overwrite.'
  verify_backup "$directory" >/dev/null
  require_docker
  dump_name="$(safe_dump_name "$manifest")"
  dump_file="$directory/$dump_name"

  printf 'Stopping Flow writers before restore...\n'
  docker compose --profile worker-demo stop "${WRITER_SERVICES[@]}"
  if ! snapshot="$(create_backup "$BACKUP_ROOT/pre-restore" | sed -n 's/^Backup: //p')"; then
    fail 'Pre-restore snapshot failed; services remain stopped and the target database was not overwritten.'
  fi
  printf 'Pre-restore snapshot: %s\n' "$snapshot"
  if ! {
    gzip -cd -- "$dump_file"
    printf '\nGRANT ALL PRIVILEGES ON flow_identity.* TO '\''flow_app'\''@'\''%%'\'';\n'
    for schema in flow_metadata flow_scheduler flow_engine flow_dispatcher; do
      printf "GRANT ALL PRIVILEGES ON %s.* TO 'flow_app'@'%%';\\n" "$schema"
    done
  } | docker compose exec -T mysql sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --default-character-set=utf8mb4'; then
    fail "Restore import failed. Services remain stopped; recover from pre-restore snapshot: $snapshot"
  fi
  printf 'PASS: restored verified MySQL snapshot. Flow services remain stopped.\n'
  printf 'Review ./scripts/flow-ops.sh reconcile before starting services.\n'
}

write_reconciliation_report() {
  local output="$1" report_dir report_file reconcile_id created_at staging
  report_dir="$(dirname "$output")"
  mkdir -p -- "$report_dir"
  [[ ! -e "$output" ]] || fail "Report target already exists: $output"
  staging="$(mktemp "$report_dir/.flow-reconcile.XXXXXX")"
  TEMP_DIR="$staging"
  report_file="$staging"
  reconcile_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  {
    printf 'Reconcile ID: %s\nGenerated UTC: %s\nMode: read-only\n' "$reconcile_id" "$created_at"
    printf 'No RPC, job replay, lease mutation, or delete operation is performed.\n\n'
    docker compose exec -T mysql sh -ec \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --raw --skip-column-names --default-character-set=utf8mb4' <<'SQL'
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT 'scheduler.outbox', delivery_state, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_scheduler.flow_schedule_outbox WHERE delivery_state <> 'DELIVERED' GROUP BY delivery_state;
SELECT 'scheduler.accepted_trigger_without_run', 'ENGINE_ACCEPTED', COUNT(*), '-'
FROM flow_scheduler.flow_schedule_trigger t
JOIN flow_scheduler.flow_schedule_outbox o ON o.trigger_id=t.id
LEFT JOIN flow_engine.flow_workflow_run r ON r.event_id=o.event_id
WHERE t.status='ENGINE_ACCEPTED' AND r.id IS NULL;
SELECT 'scheduler.pending_trigger_without_outbox', 'PENDING_ENGINE', COUNT(*), '-'
FROM flow_scheduler.flow_schedule_trigger t
LEFT JOIN flow_scheduler.flow_schedule_outbox o ON o.trigger_id=t.id
WHERE t.status='PENDING_ENGINE' AND o.event_id IS NULL;
SELECT 'engine.dispatch_outbox', delivery_state, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_engine.flow_engine_dispatch_outbox WHERE delivery_state <> 'DELIVERED' GROUP BY delivery_state;
SELECT 'engine.delivered_dispatch_without_task', 'DELIVERED', COUNT(*), '-'
FROM flow_engine.flow_engine_dispatch_outbox o
LEFT JOIN flow_dispatcher.flow_dispatch_task t ON t.engine_run_id=o.run_id
WHERE o.delivery_state='DELIVERED' AND t.id IS NULL;
SELECT 'engine.dag_dispatch_outbox', delivery_state, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_engine.flow_dag_dispatch_outbox WHERE delivery_state <> 'DELIVERED' GROUP BY delivery_state;
SELECT 'engine.dag_cancel_outbox', delivery_state, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_engine.flow_dag_cancel_outbox WHERE delivery_state <> 'DELIVERED' GROUP BY delivery_state;
SELECT 'engine.nonterminal_workflow_run', status, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_engine.flow_workflow_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED') GROUP BY status;
SELECT 'engine.nonterminal_dag_run', status, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_engine.flow_dag_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED') GROUP BY status;
SELECT 'engine.dag_dispatch_without_task', 'DELIVERED', COUNT(*), '-'
FROM flow_engine.flow_dag_dispatch_outbox o
LEFT JOIN flow_dispatcher.flow_dispatch_task t ON t.engine_run_id=o.node_run_id
WHERE o.delivery_state='DELIVERED' AND t.id IS NULL;
SELECT 'dispatcher.nonterminal_task', status, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_dispatcher.flow_dispatch_task WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED') GROUP BY status;
SELECT 'dispatcher.expired_lease', status, COUNT(*), COALESCE(DATE_FORMAT(MIN(lease_until), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_dispatcher.flow_dispatch_task
WHERE status='LEASED' AND lease_until <= UTC_TIMESTAMP(3) GROUP BY status;
SELECT 'dispatcher.outcome_outbox', delivery_state, COUNT(*), COALESCE(DATE_FORMAT(MIN(created_at), '%Y-%m-%dT%H:%i:%sZ'), '-')
FROM flow_dispatcher.flow_dispatch_outcome_outbox WHERE delivery_state <> 'DELIVERED' GROUP BY delivery_state;
SELECT 'dispatcher.delivered_outcome_without_inbox', 'DELIVERED', COUNT(*), '-'
FROM flow_dispatcher.flow_dispatch_outcome_outbox o
LEFT JOIN flow_engine.flow_engine_outcome_inbox i ON i.event_id=o.event_id
WHERE o.delivery_state='DELIVERED' AND i.event_id IS NULL;
COMMIT;
SQL
  } > "$report_file"
  chmod 600 "$report_file"
  mv -- "$report_file" "$output"
  TEMP_DIR=''
  printf 'Reconciliation report: %s\nReconcile ID: %s\n' "$output" "$reconcile_id"
}

main() {
  local command="${1:-help}"
  shift || true
  case "$command" in
    backup)
      [[ $# -eq 0 ]] || fail 'backup does not accept positional arguments.'
      require_docker
      create_backup "$BACKUP_ROOT"
      ;;
    verify)
      [[ $# -eq 1 ]] || fail 'verify requires exactly one backup directory.'
      verify_backup "$1"
      ;;
    restore)
      [[ $# -ge 1 && $# -le 2 ]] || fail 'restore requires a directory and --confirm-overwrite.'
      restore_backup "$1" "${2:-}"
      ;;
    reconcile)
      [[ $# -le 1 ]] || fail 'reconcile accepts at most one report path.'
      require_docker
      write_reconciliation_report "${1:-$BACKUP_ROOT/reconcile-$(date -u +%Y%m%dT%H%M%SZ).txt}"
      ;;
    help|-h|--help)
      usage
      ;;
    *)
      usage >&2
      fail "Unknown command: $command"
      ;;
  esac
}

cd "$ROOT"
main "$@"
