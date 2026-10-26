# OTRYX Flow Developer Quickstart (Nacos 3.2.4)
This project implements the M1 baseline and M2 Scheduler, Engine and Dispatcher. This Nacos-only registry migration is a new PR candidate; do not claim verification before its CI passes.
See [Nacos bootstrap](nacos-registry.md) and [developer Compose operations](m1-developer-compose.md).

```bash
cp .env.example .env
# Replace every secret placeholder, including FLOW_NACOS_AUTH_TOKEN and FLOW_NACOS_IDENTITY_VALUE.
docker compose build
bash scripts/start_dev_compose.sh
docker compose ps
# http://127.0.0.1:8088 is bound only to localhost
```

Startup waits for a successful **exclusive Nacos V2 Mapping bootstrap** before starting any Java RPC application. It launches Metadata, Scheduler×2, Dispatcher×2, Engine, API and the Web UI in dependency order. The Nacos adapter supports only isolated TRUSTED_DEVELOPMENT. Worker SDK and public HTTPS remain future work.
