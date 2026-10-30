#!/usr/bin/env python3
"""Validate real OTRYX RPC Prometheus samples and their low-cardinality semantics."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

SAMPLE = re.compile(
    r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+"
    r"([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)(?:\s|$)"
)
LABEL = re.compile(r'([a-zA-Z_][\w]*)="((?:\\.|[^"\\])*)"')
RPC_PREFIX = "otryx_rpc_"
CALL_COUNT = "otryx_rpc_client_calls_seconds_count"
ATTEMPT_COUNT = "otryx_rpc_client_attempts_seconds_count"
RETRY_COUNT = "otryx_rpc_client_retries_total"
TIMEOUT_COUNT = "otryx_rpc_client_timeouts_total"
FORBIDDEN_KEYS = {
    "projectid", "runid", "noderunid", "attemptid", "traceid", "userid",
    "username", "authorization", "cookie", "token", "endpoint", "service",
    "method", "path", "uri", "exception", "requestbody",
}


@dataclass(frozen=True)
class Sample:
    """One metric sample from the trusted internal management endpoint."""

    name: str
    labels: dict[str, str]
    value: float


def parse_samples(exposition: str) -> list[Sample]:
    """Read numerical Prometheus text samples; ignore HELP/TYPE metadata."""
    result: list[Sample] = []
    for raw in exposition.splitlines():
        if not raw or raw.startswith("#"):
            continue
        match = SAMPLE.match(raw)
        if match is None:
            continue
        name, label_text, numeric = match.groups()
        labels = dict(LABEL.findall(label_text or ""))
        result.append(Sample(name=name, labels=labels, value=float(numeric)))
    return result


def count(samples: list[Sample], name: str, status: str | None = None) -> float:
    """Aggregate time-series counts without relying on project-specific tags."""
    return sum(
        sample.value for sample in samples
        if sample.name == name and (status is None or sample.labels.get("status") == status)
    )


def check_rpc_labels(samples: list[Sample]) -> list[str]:
    """Reject business/identity tag keys and unsafe values on OTRYX RPC meters."""
    errors = []
    for item in samples:
        if not item.name.startswith(RPC_PREFIX):
            continue
        for key, value in item.labels.items():
            canonical = re.sub(r"[^a-z0-9]", "", key.lower())
            if canonical in FORBIDDEN_KEYS:
                errors.append(f"{item.name}: forbidden metric label key {key}")
            if len(value) > 100 or re.search(r"(?i)bearer |secret[-=]|password=", value):
                errors.append(f"{item.name}: suspicious metric label value on {key}")
    return errors


def verify(before: str, after: str, min_success: int) -> dict[str, float]:
    """Verify successful real RPC calls and their network attempts after HTTP control traffic."""
    baseline = parse_samples(before)
    observed = parse_samples(after)
    violations = check_rpc_labels(observed)
    if violations:
        raise ValueError("; ".join(sorted(set(violations))))
    names = {sample.name for sample in observed}
    if CALL_COUNT not in names or ATTEMPT_COUNT not in names:
        raise ValueError(
            "Missing real OTRYX RPC logical-call or network-attempt timer count; "
            "a configured dependency is not sufficient proof."
        )
    calls = count(observed, CALL_COUNT, "OK") - count(baseline, CALL_COUNT, "OK")
    attempts = count(observed, ATTEMPT_COUNT) - count(baseline, ATTEMPT_COUNT)
    retries = count(observed, RETRY_COUNT) - count(baseline, RETRY_COUNT)
    timeouts = count(observed, TIMEOUT_COUNT) - count(baseline, TIMEOUT_COUNT)
    if calls < min_success or attempts < calls:
        raise ValueError(
            f"Unexpected RPC accounting: successful logical calls delta={calls}, "
            f"network attempts delta={attempts}, required successful calls={min_success}"
        )
    if retries < 0 or timeouts < 0:
        raise ValueError("OTRYX RPC retry or timeout counter unexpectedly decreased")
    return {"successful_calls": calls, "network_attempts": attempts, "retries": retries, "timeouts": timeouts}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, type=Path)
    parser.add_argument("--after", required=True, type=Path)
    parser.add_argument("--min-success", type=int, default=3)
    args = parser.parse_args()
    if args.min_success < 1:
        parser.error("--min-success must be positive")
    try:
        result = verify(
            args.before.read_text(encoding="utf-8"),
            args.after.read_text(encoding="utf-8"),
            args.min_success,
        )
    except (OSError, ValueError) as failure:
        print(f"FAIL: RPC metrics evidence: {failure}", file=sys.stderr)
        return 1
    print(
        "PASS: real RPC metrics; "
        + ", ".join(f"{name}={value:g}" for name, value in result.items())
        + " (retry/timeout deltas may be zero in healthy traffic)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
