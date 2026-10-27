#!/usr/bin/env python3
"""Unit tests for the OTRYX Flow Java style audit."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_java_style import inspect_source  # noqa: E402


class JavaStyleAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.source = self.root / "Example.java"

    def test_accepts_documented_type_with_supported_format(self) -> None:
        self.source.write_text(
            """package example;

/**
 * 示例服务的公开契约。
 *
 * @Author Flow Maintainers
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 10:30
 */
public final class Example {
    /** 返回固定状态。 */
    public String status() {
        return "OK";
    }
}
""",
            encoding="utf-8",
        )
        self.assertEqual([], inspect_source(self.source, require_type_metadata=True))

    def test_detects_wildcard_import_and_long_line(self) -> None:
        self.source.write_text(
            """package example;
import java.util.*;
/**
 * 示例服务。
 *
 * @Author Flow Maintainers
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 10:30
 */
public final class Example {
    private static final String MESSAGE = "This is a deliberately long source line used to check the maximum line length in this small unit test fixture.";
}
""",
            encoding="utf-8",
        )
        rules = {str(item["rule"]) for item in inspect_source(self.source, require_type_metadata=True)}
        self.assertIn("wildcard-import", rules)
        self.assertIn("line-length", rules)

    def test_requires_metadata_for_changed_types(self) -> None:
        self.source.write_text(
            """package example;

/** 一个缺少元数据的示例类型。 */
public final class Example {
}
""",
            encoding="utf-8",
        )
        rules = {str(item["rule"]) for item in inspect_source(self.source, require_type_metadata=True)}
        self.assertIn("missing-type-metadata", rules)

    def test_audit_mode_does_not_require_legacy_metadata(self) -> None:
        self.source.write_text(
            """package example;

/** 历史类型，暂未补充元数据。 */
public final class Example {
}
""",
            encoding="utf-8",
        )
        rules = {str(item["rule"]) for item in inspect_source(self.source, require_type_metadata=False)}
        self.assertIn("missing-type-metadata", rules)


if __name__ == "__main__":
    unittest.main()
