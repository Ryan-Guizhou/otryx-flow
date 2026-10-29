#!/usr/bin/env python3
"""Unit tests for real OTRYX RPC metric evidence validation."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_rpc_metric_evidence import check_rpc_labels, parse_samples, verify  # noqa: E402


def metrics(calls: int, attempts: int, retries: int = 0) -> str:
    return (
        '# HELP otryx_rpc_client_calls_seconds Call latency\n'
        f'otryx_rpc_client_calls_seconds_count{{status="OK",category="NONE"}} {calls}\n'
        f'otryx_rpc_client_attempts_seconds_count{{status="OK",category="NONE"}} {attempts}\n'
        f'otryx_rpc_client_retries_total {retries}\n'
        'flow_schedule_enabled 3\n'
    )


class RpcMetricEvidenceTest(unittest.TestCase):
    def test_successful_calls_and_network_attempts_are_distinct(self) -> None:
        result = verify(metrics(12, 13, 1), metrics(17, 20, 3), 5)
        self.assertEqual(5.0, result["successful_calls"])
        self.assertEqual(7.0, result["network_attempts"])
        self.assertEqual(2.0, result["retries"])

    def test_missing_rpc_timers_does_not_claim_success(self) -> None:
        with self.assertRaisesRegex(ValueError, "Missing real OTRYX RPC"):
            verify("", 'flow_schedule_enabled 8\n', 3)

    def test_attempt_accounting_detects_invalid_metrics(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unexpected RPC accounting"):
            verify(metrics(0, 0), metrics(5, 2), 3)

    def test_sensitive_labels_are_rejected(self) -> None:
        sample = parse_samples(
            'otryx_rpc_client_calls_seconds_count{status="OK",traceId="sensitive"} 1\n'
        )
        self.assertTrue(check_rpc_labels(sample))
        with self.assertRaisesRegex(ValueError, "forbidden metric label"):
            verify(metrics(0, 0), metrics(5, 5)
                   + 'otryx_rpc_client_retries_total{project_id="private"} 1\n', 3)

    def test_accepts_known_low_cardinality_status_labels(self) -> None:
        samples = parse_samples(metrics(3, 3))
        self.assertEqual([], check_rpc_labels(samples))


if __name__ == "__main__":
    unittest.main()
