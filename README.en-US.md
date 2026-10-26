# OTRYX Flow

[简体中文](README.md) | [English](README.en-US.md)

> **Verified/merged: M1, FLOW-008 (#10), FLOW-009 (#11), FLOW-010 (#12), and FLOW-011 (#13)**; `main@7a40a48f762704a09e8418fd31287e9fd232939f`. **FLOW-012 (#7) Dispatcher/Lease/Fencing is a development candidate pending the new PR's real CI.** Worker SDK and public HTTPS remain pending.

<!-- doc-section:overview -->
## Overview
OTRYX Flow is a proposed distributed job scheduler and DAG workflow platform, using **OTRYX RPC** as the sole synchronous inter-service RPC stack. Intended deliveries include a Java Worker SDK, a Vue 3 administration site, independent services, recovery mechanisms, Docker images, a controlled HTTPS demo site, and auditable acceptance results.

The first release emphasizes trusted Java handlers, durable triggers, at-least-once task execution, leases, and fencing. It cannot offer general exactly-once side effects and must not expose arbitrary code execution on the public demo.

<!-- doc-section:architecture -->
## Architecture and baseline
- **Verified OTRYX RPC baseline**: `1.0.0-SNAPSHOT` / JDK 21 / Spring Boot 3.5.4; sole starter `com.peachsoft.otryx:otryx-spring-boot-starter`, **not published on Maven Central**. Pin a tested Git revision before implementing; see [source evidence](docs/09-references/source-evidence.md).
- **Proposed app stack**: Java 21 / Vue 3 + TypeScript / MySQL 8.4 LTS / Flyway / Etcd / Docker Compose. Redis, RocketMQ, and Kubernetes are not mandatory dependencies for M1.
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
    Runtime -. Application Discovery .-> Registry[(Etcd Protected Registry)]
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
## M1 verified; M2 in progress
M1 passed Maven, Vue, source, docs and real cross-JVM Docker Compose / Etcd / MySQL E2E and was squash-merged in [PR #2](https://github.com/Ryan-Guizhou/otryx-flow/pull/2).

The implemented M2 FLOW-008 [PR #10](https://github.com/Ryan-Guizhou/otryx-flow/pull/10) adds job drafts, durable idempotent creation, optimistic draft CAS, immutable published revisions, and a Vue Jobs page. See [M2 RPC/REST contracts](docs/04-api/m2-job-contracts.md) and [M2 acceptance](docs/06-testing/m2-job-acceptance.md). FLOW-009 merged in [PR #11](https://github.com/Ryan-Guizhou/otryx-flow/pull/11), adding a pure Cron/IANA time-zone preview ([design](docs/03-design/cron-timezone.md) · [API](docs/04-api/cron-preview.md)). FLOW-010 was merged in [PR #12](https://github.com/Ryan-Guizhou/otryx-flow/pull/12) with a two-replica durable Scheduler, unique Trigger and transactional Outbox ([implementation](docs/03-design/m2-scheduler-implementation.md) · [API](docs/04-api/m2-scheduler-contracts.md) · [acceptance](docs/06-testing/m2-scheduler-acceptance.md)); FLOW-011 was merged in [PR #13](https://github.com/Ryan-Guizhou/otryx-flow/pull/13), adding an independent Engine service, idempotent Inbox and reliable Outbox redelivery ([design](docs/03-design/m2-engine-outbox.md) · [RPC/REST](docs/04-api/m2-engine-contracts.md) · [acceptance](docs/06-testing/m2-engine-acceptance.md)). FLOW-012 [Issue #7](https://github.com/Ryan-Guizhou/otryx-flow/issues/7) proposes a durable Dispatcher queue, epoch-fenced Worker leases, and Engine dispatch Outbox ([design](docs/03-design/m2-dispatcher-leases.md) · [RPC/REST](docs/04-api/m2-dispatcher-contracts.md) · [acceptance](docs/06-testing/m2-dispatcher-acceptance.md)). **Worker SDK automation and Engine final outcome aggregation remain future work**; public deployment remains incomplete.

Do not deploy `TRUSTED_DEVELOPMENT` on untrusted networks.

<!-- doc-section:contribute -->
## Engineering
[AGENTS.md](AGENTS.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Issue backlog](docs/08-roadmap/issue-backlog.md). Repository documentation is the canonical design source.

<!-- doc-section:license -->
## License
MIT is proposed; the bundle includes its license text. Dependencies and visual assets require separate license review before release.
