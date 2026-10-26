# Developer quick start: M1 source candidate

M0 design documents are retained, and M1 now contains a Java/Vue/Compose implementation candidate. This is **not** yet an E2E-verified release. See [M1 development Compose guide](m1-developer-compose.md) for complete prerequisites, exact commands and troubleshooting.

```bash
# From the repository root on a networked Docker host:
cp .env.example .env
# Edit all secrets and the admin password. NEVER commit .env.
docker compose build
docker compose up -d
# Local developer website: http://127.0.0.1:8088
set -a; . ./.env; set +a
bash scripts/test_m1_e2e.sh
```

M1 real features are authenticated login, project list/create, and an API-to-metadata RPC call using an Etcd application registry. The Scheduler, Engine, Dispatcher, Worker SDK and live DAG editor remain planned for M2/M3. No production security or public hosting support is claimed; `TRUSTED_DEVELOPMENT` is restricted to the developer network.

Before marking M1 finished, collect raw build, E2E and browser evidence and update the [validation report](../06-testing/m1-validation-report.md). For full product milestones refer to [the roadmap](../08-roadmap/issue-backlog.md).
