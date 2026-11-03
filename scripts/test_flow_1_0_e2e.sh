#!/usr/bin/env bash
# Unit checks always run. The browser test runs only when the local Compose stack is already up.
# A skipped browser test is not an AT-23 pass.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./mvnw -B -ntp -pl flow-engine,flow-dispatcher,flow-metadata,flow-scheduler,flow-api,flow-worker-sdk -am test

if ! command -v docker >/dev/null 2>&1 || ! docker compose ps --status running --services 2>/dev/null | grep -qx 'flow-web'; then
  printf 'AT-23 NOT RUN: flow-web is not running. Start it with ./scripts/dev.sh up before claiming the browser path.\n'
  exit 0
fi

set -a
# shellcheck disable=SC1091
source .env
set +a
cd flow-web
FLOW_E2E=1 npx playwright test --config playwright.real.config.ts
