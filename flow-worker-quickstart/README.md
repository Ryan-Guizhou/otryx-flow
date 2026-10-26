# OTRYX Flow Worker Quickstart

Java 21 + Spring Boot 3.5.4 + OTRYX RPC `1.0.0-SNAPSHOT`, using Nacos 3.2.4 for **application-level Consumer** registration. This Quickstart embeds the reusable [`flow-worker-sdk`](../flow-worker-sdk) instead of exposing a public HTTP task execution API.

## Running in the isolated development Compose network

From repository root, configure `.env`, build the pinned RPC source and start the normal services. The worker is **not** started automatically, to keep the Dispatcher fencing E2E deterministic:

```bash
docker compose build
bash scripts/start_dev_compose.sh
docker compose --profile worker-demo up -d flow-worker-demo
docker compose --profile worker-demo logs --tail=100 flow-worker-demo
```

The only registered handler is `safe.echo` (no external side effects). It is defined directly in `QuickstartWorkerLauncher`, and cannot be replaced by remote class names, scripts or uploaded JARs. The SDK polls Dispatcher by `handlerKey`, obtains a bounded lease, executes the Handler, and confirms success/failure using `leaseEpoch` and `leaseToken`. Unknown RPC completion responses remain ambiguous until the Dispatcher lease recovers.

## Building a custom trusted worker

Use `WorkerRuntime(dispatcherRpc, WorkerSettings.development("my-worker-1"), Map.of("safe.echo", handler))` after obtaining a Spring `@OtryxRpcReference` to `DispatcherRpc`. Handlers should use `WorkerContext.checkCancelled()` during blocking work, and must implement idempotency for any external side effect. Shutdown via `WorkerRuntime.close()` to stop new leases and drain in-flight tasks.

**This is a developer-only demo.** Current Nacos Adapter accepts only `TRUSTED_DEVELOPMENT`; do not expose it on untrusted networks. Engine terminal outcome aggregation, full workflow execution and public HTTPS are not part of this example.
