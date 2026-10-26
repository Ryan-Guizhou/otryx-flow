#!/usr/bin/env bash
# Verifies all M1 checks that do not require Maven Central, Docker, MySQL, Etcd or a browser.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check_docs.py
python3 scripts/check_m1_source.py
bash -n scripts/test_m1_e2e.sh
node --experimental-strip-types --test scripts/test_frontend_api.mjs
node scripts/test_e2e_harness_offline.mjs
if command -v tsc >/dev/null; then
  tsc --noEmit --target ES2022 --lib ES2022,DOM --module ESNext --moduleResolution bundler flow-web/src/api.ts
else
  echo 'SKIP: tsc is not installed; use npm run build on a networked machine'
fi
if command -v javac >/dev/null; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  javac --release 21 -d "$tmp" flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/ProjectView.java \
    flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/ProjectListReply.java \
    flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/ProjectCreateCommand.java \
    flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/ProjectCreatedReply.java
  echo 'PASS: Java 21 DTO model classes compiled with javac'
else
  echo 'SKIP: javac unavailable; full Maven build still required'
fi
echo 'PASS: available M1 offline checks; full Maven, Vue build, real RPC E2E and browser remain unverified'
