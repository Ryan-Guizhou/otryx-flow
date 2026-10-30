# OTRYX Flow Worker Quickstart

Java 21 + Spring Boot 3.5.4 + OTRYX RPC `1.0.0-SNAPSHOT`, using Nacos 3.2.4 for **application-level Consumer** registration. This Quickstart embeds the reusable [`flow-worker-sdk`](../flow-worker-sdk/pom.xml) instead of exposing a public HTTP task execution API.

## Running in the isolated development Compose network

From repository root, run the developer startup script. It builds the pinned RPC source, starts the normal services, and starts the allowlisted demo Worker:

```bash
./scripts/dev.sh up
docker compose --profile worker-demo logs --tail=100 flow-worker-demo
```

Registered handlers are `safe.echo` (no side effects) and `safe.fail` (intentional failure for bounded retry E2E only). It is defined directly in `QuickstartWorkerLauncher`, and cannot be replaced by remote class names, scripts or uploaded JARs. The SDK polls Dispatcher by `handlerKey`, obtains a bounded lease, executes the Handler, and confirms success/failure using `leaseEpoch` and `leaseToken`. Unknown RPC completion responses remain ambiguous until the Dispatcher lease recovers.

## Building a custom trusted worker

Use `WorkerRuntime(dispatcherRpc, WorkerSettings.development("my-worker-1"), Map.of("safe.echo", handler))` after obtaining a Spring `@OtryxRpcReference` to `DispatcherRpc`. Handlers should use `WorkerContext.checkCancelled()` during blocking work, and must implement idempotency for any external side effect. Shutdown via `WorkerRuntime.close()` to stop new leases and drain in-flight tasks.

**This is a developer-only demo.** Current Nacos Adapter accepts only `TRUSTED_DEVELOPMENT`; do not expose it on untrusted networks. Full DAG execution, cancellation, retry, and terminal reconciliation are implemented across the Flow services. Production mTLS and public HTTPS remain future work.
