#!/usr/bin/env python3
"""Unit tests for strict OTRYX RPC trace evidence validation."""

from __future__ import annotations

import unittest

from check_observability_evidence import find_parent_child_pair, parse_spans


TRACE_ID = "0123456789abcdef0123456789abcdef"
CLIENT_SPAN_ID = "1111111111111111"
SERVER_SPAN_ID = "2222222222222222"


def span_block(
    service: str,
    scope: str,
    kind: str,
    span_id: str,
    parent_id: str,
    method_id: int,
    trace_id: str = TRACE_ID,
) -> list[str]:
    """Build a minimal detailed Collector span block."""
    return [
        "ResourceSpans #0",
        "Resource attributes:",
        f"     -> service.name: Str({service})",
        "ScopeSpans #0",
        f"InstrumentationScope {scope} 1.0.0",
        "Span #0",
        f"    Trace ID       : {trace_id}",
        f"    Parent ID      : {parent_id}",
        f"    ID             : {span_id}",
        f"    Name           : {service}/{method_id}",
        f"    Kind           : {kind}",
        "Attributes:",
        "         -> rpc.system: Str(otryx)",
        f"         -> rpc.method.id: Int({method_id})",
    ]


class ObservabilityEvidenceTest(unittest.TestCase):
    def test_accepts_exact_cross_service_rpc_parent_child_pair(self) -> None:
        lines = span_block(
            "flow-api",
            "com.peachsoft.otryx",
            "Client",
            CLIENT_SPAN_ID,
            "",
            7,
        ) + span_block(
            "flow-engine",
            "com.peachsoft.otryx",
            "Server",
            SERVER_SPAN_ID,
            CLIENT_SPAN_ID,
            7,
        )

        pair = find_parent_child_pair(parse_spans(lines))

        self.assertIsNotNone(pair)
        client, server = pair
        self.assertEqual(TRACE_ID, client.trace_id)
        self.assertEqual(CLIENT_SPAN_ID, server.parent_span_id)
        self.assertNotEqual(client.service_name, server.service_name)

    def test_rejects_shared_trace_id_without_parent_child_relation(self) -> None:
        lines = span_block(
            "flow-api",
            "com.peachsoft.otryx",
            "Client",
            CLIENT_SPAN_ID,
            "",
            7,
        ) + span_block(
            "flow-engine",
            "com.peachsoft.otryx",
            "Server",
            SERVER_SPAN_ID,
            "3333333333333333",
            7,
        )

        self.assertIsNone(find_parent_child_pair(parse_spans(lines)))

    def test_rejects_different_method_ids(self) -> None:
        lines = span_block(
            "flow-api",
            "com.peachsoft.otryx",
            "Client",
            CLIENT_SPAN_ID,
            "",
            7,
        ) + span_block(
            "flow-engine",
            "com.peachsoft.otryx",
            "Server",
            SERVER_SPAN_ID,
            CLIENT_SPAN_ID,
            8,
        )

        self.assertIsNone(find_parent_child_pair(parse_spans(lines)))

    def test_ignores_non_collector_compose_logs(self) -> None:
        lines = [
            "2026-10-28T00:00:00Z flow-api-1 | ResourceSpans #0",
            "2026-10-28T00:00:00Z flow-api-1 |      -> service.name: Str(fake)",
            "2026-10-28T00:00:00Z flow-api-1 | Span #0",
            "2026-10-28T00:00:00Z flow-api-1 |     Trace ID : "
            + TRACE_ID,
            "2026-10-28T00:00:00Z otel-collector-1 | ResourceSpans #0",
            "2026-10-28T00:00:00Z otel-collector-1 | "
            + "     -> service.name: Str(flow-api)",
            "2026-10-28T00:00:00Z otel-collector-1 | "
            + "InstrumentationScope org.springframework.boot 3.5.4",
        ]

        spans = parse_spans(lines)

        self.assertEqual([], spans)


if __name__ == "__main__":
    unittest.main()
