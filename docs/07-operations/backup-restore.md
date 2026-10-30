# Local MySQL backup, restore, and reconciliation

The local Compose profile stores the Flow source-of-truth data in five MySQL schemas: identity, metadata, scheduler, engine, and dispatcher. `scripts/flow-ops.sh` provides a logical backup, integrity verification, explicitly confirmed restore, and read-only reconciliation report for this isolated development stack.

These commands do not claim a production RPO/RTO, binary-log point-in-time recovery, or atomic consistency with Nacos. Keep schema migrations paused while taking a backup. Nacos application registrations are rebuilt by the existing bootstrap/start sequence; they are not part of the MySQL artifact.

## Create and verify a backup

Run from the repository root with the Compose stack and MySQL healthy:

```bash
FLOW_BACKUP_DIR=/path/outside/the/repository ./scripts/flow-ops.sh backup
FLOW_BACKUP_DIR=/path/outside/the/repository ./scripts/flow-ops.sh verify /path/outside/the/repository/flow-<timestamp>-<pid>
```

The backup is a compressed `mysqldump --single-transaction` of all five Flow schemas. Each artifact directory contains the dump, `SHA256SUMS`, and a JSON manifest with UTC creation time, repository SHA, schema list, format, and digest. The directory is private (`0700`); files are private (`0600`). The script does not print database credentials. Store production backups in an encrypted, access-controlled location separate from the application host; this local helper does not encrypt artifacts.

The default output is `backups/` under the checkout, which is ignored by Git. Set `FLOW_BACKUP_DIR` to another directory for normal use. A dump or manifest that fails validation is rejected before restore begins.

## Restore a verified artifact

Restore overwrites all five schemas. The explicit confirmation flag is mandatory. The command stops the web/API, metadata, scheduler, engine, dispatcher, and demo Worker writers; creates a pre-restore snapshot; imports the verified artifact; and leaves application services stopped.

```bash
FLOW_BACKUP_DIR=/path/outside/the/repository \
  ./scripts/flow-ops.sh restore /path/to/backup/flow-<timestamp>-<pid> --confirm-overwrite
./scripts/flow-ops.sh reconcile /path/to/recovery-reconcile.txt
```

Review the reconciliation output and service logs before starting the stack with `./scripts/dev.sh up`. Starting the scheduler can process due triggers. If the import fails, services remain stopped and the command reports the location of the pre-restore snapshot. Do not restart writers until the data state has been reviewed.

## Read-only reconciliation

```bash
./scripts/flow-ops.sh reconcile
```

The report has a unique `reconcileId` and reads one MySQL consistent snapshot. It summarizes unresolved Scheduler/Engine/Dispatcher outboxes, nonterminal Runs/tasks, expired leases, and cross-schema missing-record signals. It performs no RPC, replay, lease mutation, or delete. Rows indicate conditions requiring operator review; they are not automatic repair instructions. In particular, do not blindly retry work whose external side effects are unknown.

## Retention and current limits

Dispatcher execution logs are deleted in bounded batches of at most 10,000 rows after 14 days. Run, NodeRun, Attempt, definitions, Inbox, and Outbox records are retained. Per-project history retention and archival are not implemented; automatic deletion of execution state is intentionally absent until referential and in-progress-run guarantees are designed and tested.

This helper covers the single MySQL instance in the isolated Compose stack. It does not back up Nacos state, credentials, Docker images, or external Worker side effects, and it does not provide incremental/PITR recovery. Record backup time, restore duration, application SHA, migration state, and any manually resolved items when conducting a recovery exercise.
