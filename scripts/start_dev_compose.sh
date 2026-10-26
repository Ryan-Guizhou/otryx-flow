#!/usr/bin/env bash
# Start Nacos with offline V2 Config shards bootstrapped before RPC Provider/Consumer membership.
set -euo pipefail

docker compose up -d mysql nacos-bootstrap

# Initialization must finish successfully before any RPC application is started.
bootstrap_id="$(docker compose ps -aq nacos-bootstrap)"
if [ -z "$bootstrap_id" ]; then
  echo "ERROR: Nacos V2 bootstrap container missing" >&2
  exit 1
fi
bootstrap_ready=0
for attempt in $(seq 1 120); do
  status="$(docker inspect --format '{{.State.Status}}:{{.State.ExitCode}}' "$bootstrap_id" 2>/dev/null || true)"
  if [ "$status" = "exited:0" ]; then
    bootstrap_ready=1
    break
  fi
  case "$status" in
    exited:*|dead:*)
      echo "ERROR: Nacos V2 bootstrap failed: $status" >&2
      docker compose logs --no-color --tail=120 nacos-bootstrap >&2 || true
      exit 1
      ;;
  esac
  sleep 2
done
if [ "$bootstrap_ready" -ne 1 ]; then
  echo "ERROR: Nacos V2 bootstrap exceeded its 240-second startup deadline" >&2
  docker compose logs --no-color --tail=120 nacos nacos-bootstrap >&2 || true
  exit 1
fi
echo 'PASS: Nacos V2 8-shard mapping bootstrap completed before RPC start'

start_wait() {
  local service="$1" application="$2"
  docker compose up -d "$service"
  for attempt in $(seq 1 65); do
    if docker compose logs --no-color --tail=150 "$service" 2>/dev/null | grep -Fq "Started ${application} in"; then
      echo "PASS: ${service} initialized RPC application membership"
      return 0
    fi
    if docker compose ps --services --status exited | grep -qx "$service"; then
      echo "ERROR: ${service} exited before RPC registration" >&2
      docker compose logs --no-color --tail=75 "$service" >&2 || true
      return 1
    fi
    sleep 2
  done
  echo "ERROR: ${service} did not reach ready state within the bounded startup window" >&2
  docker compose logs --no-color --tail=75 "$service" >&2 || true
  return 1
}

start_wait flow-metadata FlowMetadataApplication
start_wait flow-scheduler FlowSchedulerApplication
start_wait flow-scheduler-replica FlowSchedulerApplication
start_wait flow-dispatcher FlowDispatcherApplication
start_wait flow-dispatcher-replica FlowDispatcherApplication
start_wait flow-engine FlowEngineApplication
start_wait flow-api FlowApiApplication
docker compose up -d flow-web
echo 'PASS: all OTRYX Flow services started in dependency order'
