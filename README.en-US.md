# OTRYX Flow

[简体中文](README.md) | [English](README.en-US.md)

> **M1 source candidate (2026-10-26), not a verified release.** The API, metadata provider, shared RPC contract, Vue frontend and developer Compose sources now exist. Network Maven/npm builds, actual cross-JVM E2E, GitHub CI and public deployment are NOT RUN.

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
## M1 delivery status and limitations
Java modules `flow-contracts`, `flow-api`, `flow-metadata`, a Vue 3 administration UI, Flyway migrations, local Compose build and CI source have been authored. `flow-api` calls `flow-metadata` via actual OTRYX RPC annotations and the Etcd application directory; business data belongs to metadata. No HTTP business fallback is used.

**Verified**: M0 documentation, M1 static source contracts, three executable Node HTTP client tests, the REST E2E script against a fake local HTTP server, and standalone Java 21 DTO compilation. Repeat offline checks with `bash scripts/verify_m1_offline.sh`. Fake HTTP results **do not** verify OTRYX RPC. Maven/Vue builds, Docker, genuine two-JVM RPC E2E, GitHub push and public-site deployment have **not been executed** because network/dependencies and Docker are unavailable.

- [M1 developer Compose setup](docs/07-operations/m1-developer-compose.md)
- [M1 validation and evidence status](docs/06-testing/m1-validation-report.md)
- [M1 implemented REST/RPC contracts](docs/04-api/m1-implemented-contracts.md)

This M1 setup uses `TRUSTED_DEVELOPMENT` in an isolated developer environment. It must not be exposed on the public internet.

<!-- doc-section:contribute -->
## Engineering
[AGENTS.md](AGENTS.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Issue backlog](docs/08-roadmap/issue-backlog.md). Repository documentation is the canonical design source.

<!-- doc-section:license -->
## License
MIT is proposed; the bundle includes its license text. Dependencies and visual assets require separate license review before release.
