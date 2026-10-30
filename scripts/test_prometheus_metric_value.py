#!/usr/bin/env python3
"""Tests canonical numeric handling for Prometheus gauge exposition values."""

import unittest

from prometheus_metric_value import normalize_metric_value


class PrometheusMetricValueTest(unittest.TestCase):
    def test_normalizes_integer_gauges_rendered_as_floats(self):
        self.assertEqual(normalize_metric_value("1.0"), "1")
        self.assertEqual(normalize_metric_value("2.000"), "2")
        self.assertEqual(normalize_metric_value("0.0"), "0")

    def test_preserves_fractional_gauge_values(self):
        self.assertEqual(normalize_metric_value("1.25"), "1.25")

    def test_rejects_non_finite_or_malformed_values(self):
        for value in ("NaN", "+Inf", "offline"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalize_metric_value(value)


if __name__ == "__main__":
    unittest.main()
