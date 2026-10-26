#!/usr/bin/env bash
# Start Etcd providers before OTRYX RPC consumers: avoid app-membership startup races.
set -euo pipefail

docker compose up -d mysql etcd

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
