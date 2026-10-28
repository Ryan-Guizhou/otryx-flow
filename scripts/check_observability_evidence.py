#!/usr/bin/env python3
"""Verify OTRYX RPC client/server parent-child propagation in OTLP Collector logs."""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

DOCKER_LOG_PATTERN = re.compile(
    r"^(?:\S+\s+)?(?P<container>[A-Za-z0-9_.-]+)\s+\|\s?(?P<message>.*)$"
)
RESOURCE_SPANS_PATTERN = re.compile(r"\bResourceSpans\s+#\d+")
SCOPE_PATTERN = re.compile(r"InstrumentationScope\s+(\S+)")
SERVICE_PATTERN = re.compile(r"service\.name:\s*Str\(([^)]+)\)")
SPAN_START_PATTERN = re.compile(r"\bSpan\s+#\d+")
TRACE_PATTERN = re.compile(r"Trace ID\s*:\s*([0-9a-f]{32})", re.IGNORECASE)
PARENT_PATTERN = re.compile(r"Parent ID\s*:\s*([0-9a-f]*)", re.IGNORECASE)
SPAN_ID_PATTERN = re.compile(r"^\s*ID\s*:\s*([0-9a-f]{16})\s*$", re.IGNORECASE)
SPAN_NAME_PATTERN = re.compile(r"^\s*Name\s*:\s*(.*?)\s*$")
SPAN_KIND_PATTERN = re.compile(r"^\s*Kind\s*:\s*(\w+)\s*$")
RPC_SYSTEM_PATTERN = re.compile(r"rpc\.system:\s*Str\(([^)]+)\)")
RPC_METHOD_PATTERN = re.compile(r"rpc\.method\.id:\s*[A-Za-z]+\((\d+)\)")
OTRYX_INSTRUMENTATION_SCOPE = "com.peachsoft.otryx"
OTRYX_RPC_SYSTEM = "otryx"


@dataclass(frozen=True)
class Span:
    """The trace fields needed to validate an OTRYX RPC client/server pair."""

    service_name: str | None
    scope_name: str | None
    trace_id: str | None
    parent_span_id: str | None
    span_id: str | None
    name: str | None
    kind: str | None
    rpc_system: str | None
    method_id: str | None

    @property
    def is_otryx_rpc(self) -> bool:
        return (
            self.scope_name == OTRYX_INSTRUMENTATION_SCOPE
            and self.rpc_system == OTRYX_RPC_SYSTEM
        )


def _collector_payload(line: str) -> str | None:
    """Return collector output, excluding unrelated Compose container logs."""
    match = DOCKER_LOG_PATTERN.match(line)
    if match is None:
        return line
    if not match.group("container").startswith("otel-collector"):
        return None
    return match.group("message")


def parse_spans(lines: Iterable[str]) -> list[Span]:
    """Parse detailed OTLP debug-exporter output into immutable span records."""
    spans: list[Span] = []
    service_name: str | None = None
    scope_name: str | None = None
    current: dict[str, str | None] | None = None

    def finish_span() -> None:
        if current is None:
            return
        if current.get("trace_id") and current.get("span_id") and current.get("name"):
            spans.append(
                Span(
                    service_name=current.get("service_name"),
                    scope_name=current.get("scope_name"),
                    trace_id=current.get("trace_id"),
                    parent_span_id=current.get("parent_span_id"),
                    span_id=current.get("span_id"),
                    name=current.get("name"),
                    kind=current.get("kind"),
                    rpc_system=current.get("rpc_system"),
                    method_id=current.get("method_id"),
                )
            )

    for raw_line in lines:
        line = _collector_payload(raw_line)
        if line is None:
            continue

        if RESOURCE_SPANS_PATTERN.search(line):
            finish_span()
            current = None
            service_name = None
            scope_name = None
            continue

        service_match = SERVICE_PATTERN.search(line)
        if service_match:
            service_name = service_match.group(1).strip()

        scope_match = SCOPE_PATTERN.search(line)
        if scope_match:
            finish_span()
            current = None
            scope_name = scope_match.group(1).strip()

        if SPAN_START_PATTERN.search(line):
            finish_span()
            current = {
                "service_name": service_name,
                "scope_name": scope_name,
                "trace_id": None,
                "parent_span_id": None,
                "span_id": None,
                "name": None,
                "kind": None,
                "rpc_system": None,
                "method_id": None,
            }
            continue

        if current is None:
            continue

        match = TRACE_PATTERN.search(line)
        if match:
            current["trace_id"] = match.group(1).lower()
            continue

        match = PARENT_PATTERN.search(line)
        if match:
            current["parent_span_id"] = match.group(1).lower()
            continue

        match = SPAN_ID_PATTERN.match(line)
        if match:
            current["span_id"] = match.group(1).lower()
            continue

        match = SPAN_NAME_PATTERN.match(line)
        if match:
            current["name"] = match.group(1).strip()
            continue

        match = SPAN_KIND_PATTERN.match(line)
        if match:
            current["kind"] = match.group(1).upper()
            continue

        match = RPC_SYSTEM_PATTERN.search(line)
        if match:
            current["rpc_system"] = match.group(1).strip().lower()
            continue

        match = RPC_METHOD_PATTERN.search(line)
        if match:
            current["method_id"] = match.group(1)

    finish_span()
    return spans


def find_parent_child_pair(spans: Iterable[Span]) -> tuple[Span, Span] | None:
    """Find a cross-service OTRYX RPC client span and its exact server child."""
    clients = [
        span
        for span in spans
        if span.is_otryx_rpc
        and span.kind == "CLIENT"
        and span.service_name
        and span.trace_id
        and span.span_id
        and span.method_id
    ]
    servers = [
        span
        for span in spans
        if span.is_otryx_rpc
        and span.kind == "SERVER"
        and span.service_name
        and span.trace_id
        and span.parent_span_id
        and span.method_id
    ]

    for client in clients:
        for server in servers:
            if (
                client.service_name != server.service_name
                and client.trace_id == server.trace_id
                and server.parent_span_id == client.span_id
                and server.method_id == client.method_id
            ):
                return client, server
    return None


def main() -> int:
    if len(sys.argv) != 2:
        print(
            "Usage: check_observability_evidence.py <collector-log-file>",
            file=sys.stderr,
        )
        return 2

    log_path = Path(sys.argv[1])
    if not log_path.is_file():
        print("Collector log file does not exist.", file=sys.stderr)
        return 2

    spans = parse_spans(
        log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    )
    pair = find_parent_child_pair(spans)
    if pair is None:
        otryx_spans = [span for span in spans if span.is_otryx_rpc]
        if not otryx_spans:
            reason = (
                "No OTRYX RPC spans with rpc.system=otryx were observed; "
                "cross-JVM RPC tracing is not proven."
            )
        else:
            reason = (
                "OTRYX RPC spans were observed, but no cross-service client/server "
                "parent-child pair was found with matching trace ID, parent span ID, "
                "and method ID."
            )
        print(reason, file=sys.stderr)
        return 1

    client, server = pair
    print(
        "PASS: verified OTRYX RPC client/server parent-child propagation: "
        f"trace_id={client.trace_id}, "
        f"client_service={client.service_name}, client_span_id={client.span_id}, "
        f"server_service={server.service_name}, "
        f"server_parent_span_id={server.parent_span_id}, "
        f"method_id={client.method_id}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
