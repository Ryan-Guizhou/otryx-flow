#!/usr/bin/env bash
set -euo pipefail

[[ "${FLOW_E2E_BACKUP_RESTORE:-0}" = 1 ]] || {
  echo 'Refusing to overwrite Compose schemas outside isolated backup/restore acceptance.' >&2
  exit 1
}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BACKUP_DIR="$WORK/backups"
MARKER_TABLE='flow_ops_restore_probe'

mysql_identity() {
  docker compose exec -T mysql sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database=flow_identity --batch --skip-column-names'
}

cd "$ROOT"
mysql_identity <<SQL
CREATE TABLE IF NOT EXISTS ${MARKER_TABLE} (id INT PRIMARY KEY, marker VARCHAR(32) NOT NULL) ENGINE=InnoDB;
REPLACE INTO ${MARKER_TABLE} (id, marker) VALUES (1, 'before-backup');
SQL

backup_output="$(FLOW_BACKUP_DIR="$BACKUP_DIR" bash scripts/flow-ops.sh backup)"
backup_dir="$(sed -n 's/^Backup: //p' <<< "$backup_output")"
[[ -n "$backup_dir" ]] || { echo 'Backup command did not return an artifact path.' >&2; exit 1; }
bash scripts/flow-ops.sh verify "$backup_dir"

mysql_identity <<SQL
UPDATE ${MARKER_TABLE} SET marker='after-backup' WHERE id=1;
SQL

FLOW_BACKUP_DIR="$BACKUP_DIR" bash scripts/flow-ops.sh restore "$backup_dir" --confirm-overwrite
reconcile_path="$WORK/reconcile.txt"
bash scripts/flow-ops.sh reconcile "$reconcile_path"

restored_marker="$(printf 'SELECT marker FROM %s WHERE id=1;\n' "$MARKER_TABLE" | mysql_identity)"
[[ "$restored_marker" = 'before-backup' ]] || {
  echo "Restore did not recover the pre-backup marker: $restored_marker" >&2
  exit 1
}
schema_history_count="$(printf '%s\n' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('flow_identity','flow_metadata','flow_scheduler','flow_engine','flow_dispatcher') AND table_name='flyway_schema_history';" | mysql_identity)"
[[ "$schema_history_count" = 5 ]] || {
  echo "Restore did not preserve all five Flyway histories: $schema_history_count" >&2
  exit 1
}

bash scripts/start_dev_compose.sh
ready=0
for attempt in $(seq 1 30); do
  status="$(curl --silent --show-error --max-time 5 -o /dev/null -w '%{http_code}' \
    http://127.0.0.1:8088/api/v1/auth/csrf || true)"
  if [[ "$status" = 200 ]]; then ready=1; break; fi
  sleep 2
done
[[ "$ready" = 1 ]] || { echo 'API did not return after restored schemas were restarted.' >&2; exit 1; }
echo 'PASS: five-schema backup, checksum, confirmed restore, read-only reconciliation, migrations, and API recovery.'
