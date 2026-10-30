# Local Core Quickstart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start the complete isolated local Flow business path with one command and keep slow observability/fault-injection workflows optional.

**Architecture:** Add a host-side development command that generates private local credentials, builds the existing pinned multi-service images, delegates ordered Nacos/RPC startup to the established startup script, and starts the trusted demo Worker. Keep the observability Compose overlay intact and make expensive Compose/telemetry GitHub Actions manual-only.

**Tech Stack:** Bash, Docker Compose V2, MySQL 8.4, Nacos 3.2.4, Java 21, Vue 3, GitHub Actions.

**Spec:** User request on 2026-10-30; current core and deployment contracts in `docs/07-operations/quickstart.md`, `docs/07-operations/nacos-registry.md`, and `AGENTS.md`.

## Global Constraints

- Preserve the existing OTRYX RPC SHA and Nacos V2 mapping bootstrap ordering.
- Generate isolated-development secrets only; never print or commit them.
- Do not remove observability code or make it a default runtime dependency.
- Keep real distributed Compose E2E available through manual workflow dispatch.
- Do not claim production mTLS, public deployment, or full acceptance from local startup.

## Review Focus

- Existing `.env` credentials must not be overwritten; an incomplete template must fail before Docker runs.
- Generated `.env` must be private and contain non-placeholder secrets.
- Worker profile must start as part of the one-command local stack while CI integration can still start deterministic base services separately.
- Default startup must not include Prometheus, Grafana, or OTel Collector.
- Slow integration/observability workflows must remain manually runnable after implementation work is complete.

---

### Task 1: One-command local startup

**Files:**
- Create: `scripts/dev.sh`
- Test: `scripts/test_dev_quickstart.sh`

**Interfaces:**
- Command: `./scripts/dev.sh up|down|ps|logs [service]`
- `up`: creates `.env` if missing, validates credentials, builds images, starts the ordered core services, starts `flow-worker-demo`, and shows service status.
- `down`: stops the stack without deleting volumes.

- [ ] Verify the quickstart regression harness fails before `scripts/dev.sh` exists.
- [ ] Implement private env creation, validation, lifecycle commands, and the happy path with a fake Docker CLI.
- [ ] Run `bash -n scripts/dev.sh scripts/test_dev_quickstart.sh` and `bash scripts/test_dev_quickstart.sh`.

### Task 2: Defer slow GitHub Actions

**Files:**
- Modify: `.github/workflows/m1-integration.yml`
- Modify: `.github/workflows/observability.yml`
- Modify: `.github/workflows/m1-quality.yml`

**Interfaces:**
- Full real Compose acceptance and observability configuration checks remain available through `workflow_dispatch`.
- The fast source/build PR workflow runs the quickstart shell regression harness.

- [ ] Confirm m1 integration and observability PR triggers are removed while manual triggers remain.
- [ ] Run source, documentation, and workflow syntax checks after the full change set is applied.

### Task 3: Align onboarding docs with shipped core

**Files:**
- Modify: `README.md`
- Modify: `README.en-US.md`
- Modify: `docs/07-operations/quickstart.md`
- Modify: `docs/07-operations/m1-developer-compose.md`
- Modify: `flow-worker-quickstart/README.md`

- [ ] Replace stale “not implemented” claims for existing Worker/DAG execution.
- [ ] Document one-command start, login password location, stop/data retention, and optional observability overlay.
- [ ] State the current production security limitations.
- [ ] Run `python3 scripts/check_docs.py`.
