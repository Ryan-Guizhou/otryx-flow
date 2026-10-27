#!/usr/bin/env python3
"""Verify that OTLP Collector logs show at least one trace shared by multiple services."""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

SERVICE_PATTERN = re.compile(r"service\.name:\s*Str\(([^)]+)\)")
TRACE_PATTERN = re.compile(r"Trace ID\s*:\s*([0-9a-f]{32})", re.IGNORECASE)


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: check_observability_evidence.py <collector-log-file>", file=sys.stderr)
        return 2

    log_path = Path(sys.argv[1])
    if not log_path.is_file():
        print("Collector log file does not exist.", file=sys.stderr)
        return 2

    current_service = None
    services_by_trace: dict[str, set[str]] = defaultdict(set)
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        service_match = SERVICE_PATTERN.search(line)
        if service_match:
            current_service = service_match.group(1).strip()
        trace_match = TRACE_PATTERN.search(line)
        if trace_match and current_service:
            services_by_trace[trace_match.group(1).lower()].add(current_service)

    shared = [services for services in services_by_trace.values() if len(services) >= 2]
    if not shared:
        print(
            "No trace ID was observed across multiple service.name values; "
            "cross-JVM trace propagation is not proven.",
            file=sys.stderr,
        )
        return 1

    service_count = max(len(services) for services in shared)
    print(f"PASS: observed a shared trace across {service_count} service identities.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
