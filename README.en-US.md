# OTRYX Flow

[简体中文](README.md) | [English](README.en-US.md)

> **Verified: M1 and M2 (Issues #3–#9); M3 FLOW-015 / Issue #19 is underway.** Production mTLS and public HTTPS remain unimplemented.

<!-- doc-section:overview -->
## Overview
OTRYX Flow is a proposed distributed job scheduler and DAG workflow platform, using **OTRYX RPC** as the sole synchronous inter-service RPC stack. Intended deliveries include a Java Worker SDK, a Vue 3 administration site, independent services, recovery mechanisms, Docker images, a controlled HTTPS demo site, and auditable acceptance results.

The first release emphasizes trusted Java handlers, durable triggers, at-least-once task execution, leases, and fencing. It cannot offer general exactly-once side effects and must not expose arbitrary code execution on the public demo.

<!-- doc-section:architecture -->
## Architecture and baseline
- **Verified OTRYX RPC baseline**: `1.0.0-SNAPSHOT` / JDK 21 / Spring Boot 3.5.4; sole starter `com.peachsoft.otryx:otryx-spring-boot-starter`, **not published on Maven Central**. Pin a tested Git revision before implementing; see [source evidence](docs/09-references/source-evidence.md).
- **Proposed app stack**: Java 21 / Vue 3 + TypeScript / MySQL 8.4 LTS / Flyway / Nacos 3.2.4 / Docker Compose. Redis, RocketMQ, and Kubernetes are not mandatory dependencies for M1.
- **Proposed applications**: `flow-api`, `flow-metadata`, `flow-scheduler`, `flow-engine`, `flow-dispatcher`, `flow-worker`, `flow-web`. OTRYX RPC handles bounded control calls; a business outbox and durable state handle reliable delivery.

```mermaid
flowchart TB
    Browser[Browser] --> TLS[HTTPS Reverse Proxy]
    TLS --> Web[flow-web]
    TLS --> API[flow-api REST]
    subgraph Runtime[OTRYX RPC Business Services]
      API --> Metadata[flow-metadata]
      API --> Scheduler[flow-scheduler]
      API --> Engine[flow-engine]
      Scheduler --> Engine
      Engine --> Dispatcher[flow-dispatcher]
      Worker[flow-worker N] --> Dispatcher
      Dispatcher --> Engine
    end
    Runtime -. Application Discovery .-> Registry[(Nacos V2 Naming + Config)]
    Runtime --> Database[(MySQL, schema per owner)]
```

<!-- doc-section:scope -->
## Intended V1 scope
1. Project roles, versioned job definitions, Cron/manual triggers and misfire policies.
2. DAG editing/validation, version publishing, durable workflow instances and node recovery.
3. Worker Pull + Lease, idempotent completion, logs, audits, monitoring, alerts, and a restricted demo.
4. Docker Compose, HTTPS site, backup/rollback, frontend/backend E2E, and fault-injection tests.

**Out of scope**: public arbitrary Shell/Python execution, general distributed transactions, every cross-language SDK, or claims of exactly-once task side effects.

<!-- doc-section:docs -->
## Documentation
Read the [docs index](docs/index.md), then [requirements](docs/01-product/requirements-prd.md), [architecture](docs/02-architecture/architecture-overview.md), [core design](docs/03-design/consistency-and-recovery.md), [frontend spec](docs/05-frontend/ui-ux-spec.md), [acceptance](docs/06-testing/acceptance-plan.md), and [roadmap](docs/08-roadmap/milestones.md).

<!-- doc-section:status -->
## M1/M2 and M3 FLOW-015 verified; FLOW-016 in progress

M1 merged in [PR #2](https://github.com/Ryan-Guizhou/otryx-flow/pull/2). M2 distributed short-job execution passed five gates and merged in final [PR #18](https://github.com/Ryan-Guizhou/otryx-flow/pull/18). M3 FLOW-015 merged in [PR #20](https://github.com/Ryan-Guizhou/otryx-flow/pull/20) after Backend, Frontend, Source, Docs and real Nacos/MySQL/OTRYX RPC Compose checks; it delivers bounded DAG definition validation, optimistic drafts and immutable revisions.

The actual DAG runtime is in [Issue #21](https://github.com/Ryan-Guizhou/otryx-flow/issues/21) / [PR #22](https://github.com/Ryan-Guizhou/otryx-flow/pull/22): node-level dispatch, conditional SKIPPED outcomes, Outbox/Inbox and recovery. **The full real multi-Worker acceptance gate has not yet passed.** See [design](docs/03-design/m3-dag-runtime.md), [API](docs/04-api/m3-dag-runtime-contracts.md), and [acceptance](docs/06-testing/m3-dag-acceptance.md).

**Boundary:** isolated TRUSTED_DEVELOPMENT + Nacos 3.2.4 only. Production mTLS, public HTTPS, full cancellation/retry and Vue Flow canvas are later milestones.

<!-- doc-section:contribute -->
## Engineering
[AGENTS.md](AGENTS.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Issue backlog](docs/08-roadmap/issue-backlog.md). Repository documentation is the canonical design source.

<!-- doc-section:license -->
## License
MIT is proposed; the bundle includes its license text. Dependencies and visual assets require separate license review before release.
