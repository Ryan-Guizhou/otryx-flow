#!/usr/bin/env bash
# CI only: Collector remains reachable while its downstream OTLP trace exporter refuses spans.
set -euo pipefail
[ "${FLOW_E2E_COLLECTOR_EXPORTER_FAULT:-0}" = 1 ] || {
  echo "Refusing exporter fault injection outside isolated CI"; exit 1;
}
: "${FLOW_ADMIN_USERNAME:?Missing admin username}"
: "${FLOW_ADMIN_PASSWORD:?Missing admin password}"
BASE="${FLOW_BASE_URL:-http://127.0.0.1:8088}"
WORK="$(mktemp -d)"
OVERRIDE=0

restore() {
  if [ "$OVERRIDE" = 1 ]; then
    docker compose up -d --no-deps --force-recreate otel-collector >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap restore EXIT

self_metrics() {
  docker compose --profile observability-test run --rm --no-deps \
    --entrypoint sh observability-probe -ec \
    'curl --silent --show-error --fail --max-time 10 http://otel-collector:8888/metrics' \
    > "$WORK/internal.prom" 2>/dev/null
}

# Inject failure only into the Collector's trace *destination*; the OTLP
# receiver and Prometheus metric exporter remain up on their normal ports.
OVERRIDE=1
docker compose -f compose.yaml -f compose.observability.yaml \
  -f compose.exporter-failure.yaml up -d --no-deps --force-recreate otel-collector
ready=0
for i in $(seq 1 30); do
  if self_metrics; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "Collector self telemetry unavailable in exporter-failure profile"; exit 1; }

# Submit a valid, fixed test-only OTLP JSON span to exercise the downstream
# exporter deterministically; this is not a synthetic business RPC metric.
TRACE='{"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"00000000000000000000000000000001","spanId":"0000000000000001","name":"collector-exporter-fault-test","startTimeUnixNano":"1761789600000000000","endTimeUnixNano":"1761789600000100000"}]}]}]}'
for i in $(seq 1 3); do
  docker compose --profile observability-test run --rm --no-deps \
    --entrypoint sh observability-probe -ec \
    'curl --fail --silent --show-error --max-time 10 -H "Content-Type: application/json" \
      --data-binary "$1" http://otel-collector:4318/v1/traces >/dev/null' sh "$TRACE"
done

failed=0
for i in $(seq 1 35); do
  if self_metrics && python3 - "$WORK/internal.prom" <<'PY'
import re
import sys
from pathlib import Path

sample = Path(sys.argv[1]).read_text(encoding="utf-8")
hits = [
    float(value)
    for value in re.findall(
        r"^otelcol_exporter_send_failed_spans_total(?:\{[^}]*\})?\s+([0-9]+(?:\.[0-9]+)?)",
        sample,
        re.MULTILINE,
    )
]
if sum(hits) < 1:
    raise SystemExit(1)
PY
  then
    failed=1
    break
  fi
  sleep 2
done
[ "$failed" = 1 ] || {
  echo "Collector was reachable, but no real exporter-send-failure metric was observed"; exit 1;
}

# The Collector and its internal telemetry remain available when its
# backend is unreachable; validate a real authenticated REST -> RPC call.
COOKIE="$WORK/cookie"
curl --fail --silent --show-error --max-time 15 \
  -c "$COOKIE" -b "$COOKIE" "$BASE/api/v1/auth/csrf" > "$WORK/csrf.json"
TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$WORK/csrf.json")"
python3 -c 'import json,os,sys;json.dump({"username":os.environ["FLOW_ADMIN_USERNAME"],"password":os.environ["FLOW_ADMIN_PASSWORD"]},open(sys.argv[1],"w"))' "$WORK/login.json"
code="$(curl --silent --show-error --max-time 15 -o /dev/null -w '%{http_code}' \
  -c "$COOKIE" -b "$COOKIE" -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $TOKEN" --data-binary @"$WORK/login.json" "$BASE/api/v1/auth/login")"
[ "$code" = 200 ] || { echo "Exporter fault affected API authentication: HTTP $code"; exit 1; }
code="$(curl --silent --show-error --max-time 15 -o /dev/null -w '%{http_code}' \
  -b "$COOKIE" "$BASE/api/v1/projects")"
[ "$code" = 200 ] || { echo "Exporter destination outage affected business RPC"; exit 1; }

restore
OVERRIDE=0
echo "PASS: Collector exporter errors were observed while receiver stayed up; real RPC returned 200"
