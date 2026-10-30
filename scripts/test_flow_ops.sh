#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
export DOCKER_CALLS="$TMP/docker-calls"
export RESTORE_CAPTURE="$TMP/restored.sql"
export QUERY_CAPTURE="$TMP/reconcile.sql"
export FLOW_BACKUP_DIR="$TMP/backups"

cat > "$TMP/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
args="$*"
printf '%s\n' "$args" >> "$DOCKER_CALLS"
case "$args" in
  *mysqldump*)
    if [[ "${DUMP_FAILURE:-0}" = 1 ]]; then
      echo 'fixture dump failure' >&2
      exit 17
    fi
    printf '%s\n' '-- fixture logical MySQL dump'
    printf '%s\n' 'USE `flow_identity`;' 'USE `flow_metadata`;' 'USE `flow_scheduler`;' \
      'USE `flow_engine`;' 'USE `flow_dispatcher`;'
    ;;
  *'--batch --raw'*)
    cat > "$QUERY_CAPTURE"
    printf '%s\n' $'flow.reconcile\tfixture\t0\t-'
    ;;
  *'mysql --user=root'*)
    cat > "$RESTORE_CAPTURE"
    ;;
  *) ;;
esac
DOCKER
chmod +x "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH"

run() { bash "$ROOT/scripts/flow-ops.sh" "$@"; }

run backup > "$TMP/backup.out"
first_backup="$(sed -n 's/^Backup: //p' "$TMP/backup.out")"
test -f "$first_backup/manifest.json"
test "$(stat -c '%a' "$first_backup/manifest.json")" = 600
run verify "$first_backup" > "$TMP/verify.out"
grep -q '^PASS: verified flow-mysql-' "$TMP/verify.out"
! grep -qi 'password\|MYSQL_PWD' "$TMP/backup.out" "$first_backup/manifest.json"

first_dump="$(find "$first_backup" -maxdepth 1 -name 'flow-mysql-*.sql.gz' -print -quit)"
printf 'tamper' >> "$first_dump"
if run verify "$first_backup" > "$TMP/tampered.out" 2>&1; then
  echo 'Tampered backup unexpectedly passed checksum validation.' >&2
  exit 1
fi

run backup > "$TMP/valid-backup.out"
valid_backup="$(sed -n 's/^Backup: //p' "$TMP/valid-backup.out")"
before_calls="$(wc -l < "$DOCKER_CALLS")"
if run restore "$valid_backup" > "$TMP/unconfirmed.out" 2>&1; then
  echo 'Restore without explicit overwrite confirmation unexpectedly succeeded.' >&2
  exit 1
fi
test "$(wc -l < "$DOCKER_CALLS")" = "$before_calls"

run restore "$valid_backup" --confirm-overwrite > "$TMP/restore.out"
grep -q '^PASS: restored verified MySQL snapshot' "$TMP/restore.out"
grep -q 'GRANT ALL PRIVILEGES ON flow_dispatcher' "$RESTORE_CAPTURE"
stop_line="$(grep -n 'compose --profile worker-demo stop' "$DOCKER_CALLS" | tail -1 | cut -d: -f1)"
dump_line="$(grep -n 'mysqldump' "$DOCKER_CALLS" | tail -1 | cut -d: -f1)"
import_line="$(grep -n 'mysql --user=root' "$DOCKER_CALLS" | tail -1 | cut -d: -f1)"
test "$stop_line" -lt "$dump_line"
test "$dump_line" -lt "$import_line"

run reconcile "$TMP/reconcile/report.txt" > "$TMP/reconcile.out"
grep -q '^Reconcile ID: ' "$TMP/reconcile.out"
grep -q '^Mode: read-only$' "$TMP/reconcile/report.txt"
grep -q 'No RPC, job replay, lease mutation, or delete operation' "$TMP/reconcile/report.txt"
grep -q 'flow_engine.flow_dag_run' "$QUERY_CAPTURE"
if grep -Eiq '\b(UPDATE|DELETE|INSERT|REPLACE|CALL)\b' "$QUERY_CAPTURE"; then
  echo 'Reconciliation query contains a mutating statement.' >&2
  exit 1
fi

before_dirs="$(find "$FLOW_BACKUP_DIR" -maxdepth 1 -type d -name 'flow-*' | wc -l)"
if DUMP_FAILURE=1 run backup > "$TMP/failure.out" 2>&1; then
  echo 'Failed mysqldump unexpectedly produced a backup.' >&2
  exit 1
fi
after_dirs="$(find "$FLOW_BACKUP_DIR" -maxdepth 1 -type d -name 'flow-*' | wc -l)"
test "$before_dirs" = "$after_dirs"
! find "$FLOW_BACKUP_DIR" -maxdepth 1 -name '.flow-backup.*' | grep -q .

echo 'PASS: backup manifest/checksum, explicit restore ordering, read-only reconcile, and failed-dump cleanup.'
