#!/usr/bin/env python3
"""Audit Java formatting and type-level documentation conventions for OTRYX Flow."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
MAX_LINE_LENGTH = 120
JAVA_SOURCE_MARKERS = ("/src/main/java/", "/src/test/java/")
WILDCARD_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?[\w.]+\.\*\s*;")
TYPE_DECLARATION = re.compile(
    r"^\s*(?:(?:public|protected|private|abstract|final|sealed|non-sealed|static)\s+)*"
    r"(?:class|interface|enum|record)\s+[A-Za-z_$][\w$]*\b|"
    r"^\s*(?:(?:public|protected|private)\s+)?@interface\s+[A-Za-z_$][\w$]*\b"
)
REQUIRED_TYPE_TAGS = ("Author", "Version", "CreateTime")
CREATE_TIME_FORMAT = re.compile(r"^\\d{4}/\\d{1,2}/\\d{1,2} \\d{2}:\\d{2}$")
PLACEHOLDER_VALUE = re.compile(r"(?i)(todo|unknown|placeholder|replace.?me|真实作者|真实时间|填写作者|填写时间|<[^>]+>)")


def is_java_source(path: Path) -> bool:
    normalized = "/" + path.as_posix().lstrip("/")
    return path.suffix == ".java" and any(marker in normalized for marker in JAVA_SOURCE_MARKERS)


def discover_java_files(root: Path = ROOT) -> list[Path]:
    return sorted(path for path in root.rglob("*.java") if is_java_source(path))


def _type_javadoc(lines: list[str], declaration_index: int) -> tuple[str | None, int | None]:
    """Return the Javadoc block immediately preceding a type after optional annotations."""
    start_limit = max(0, declaration_index - 45)
    for start in range(declaration_index - 1, start_limit - 1, -1):
        if "/**" not in lines[start]:
            continue
        end = start
        while end < declaration_index and "*/" not in lines[end]:
            end += 1
        if end >= declaration_index:
            continue
        between = lines[end + 1:declaration_index]
        annotation_depth = 0
        valid = True
        for raw_line in between:
            line = raw_line.strip()
            if not line:
                continue
            if annotation_depth > 0:
                annotation_depth += line.count("(") - line.count(")")
                continue
            if not line.startswith("@"):
                valid = False
                break
            annotation_depth = line.count("(") - line.count(")")
        if valid and annotation_depth == 0:
            return "\n".join(lines[start:end + 1]), start
        # A nearer Javadoc separated from the type by executable code is not its type Javadoc.
        break
    return None, None


def inspect_source(path: Path, require_type_metadata: bool = False) -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    try:
        raw = path.read_bytes()
    except OSError as error:
        return [{"path": str(path), "line": 1, "rule": "file-read", "message": str(error)}]

    try:
        relative = path.relative_to(ROOT).as_posix()
    except ValueError:
        relative = path.as_posix()
    if raw.startswith(b"\xef\xbb\xbf"):
        findings.append({"path": relative, "line": 1, "rule": "utf8-bom", "message": "Remove UTF-8 BOM."})
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return findings + [{"path": relative, "line": 1, "rule": "utf8", "message": "File is not valid UTF-8."}]

    if b"\r" in raw:
        findings.append({"path": relative, "line": 1, "rule": "line-ending", "message": "Use LF line endings."})

    lines = text.split("\n")
    for line_number, line in enumerate(lines, start=1):
        if "\t" in line:
            findings.append({"path": relative, "line": line_number, "rule": "tab", "message": "Use spaces, not tabs."})
        if line.rstrip(" \t") != line:
            findings.append({"path": relative, "line": line_number, "rule": "trailing-whitespace", "message": "Remove trailing whitespace."})
        if len(line) > MAX_LINE_LENGTH:
            findings.append({
                "path": relative,
                "line": line_number,
                "rule": "line-length",
                "message": f"Line has {len(line)} characters; maximum is {MAX_LINE_LENGTH}.",
            })
        if WILDCARD_IMPORT.match(line):
            findings.append({"path": relative, "line": line_number, "rule": "wildcard-import", "message": "Use explicit imports."})

    for index, line in enumerate(lines):
        if not TYPE_DECLARATION.match(line):
            continue
        javadoc, javadoc_start = _type_javadoc(lines, index)
        if javadoc is None:
            findings.append({
                "path": relative,
                "line": index + 1,
                "rule": "missing-type-javadoc",
                "message": "Add a Chinese type-level Javadoc before type annotations.",
            })
            if require_type_metadata:
                findings.append({
                    "path": relative,
                    "line": index + 1,
                    "rule": "missing-type-metadata",
                    "message": "A type Javadoc with @Author, @Version and @CreateTime is required.",
                })
            continue

        missing_tags: list[str] = []
        metadata_values: dict[str, str] = {}
        for tag in REQUIRED_TYPE_TAGS:
            match = re.search(
                r"^\\s*\\*\\s*@" + re.escape(tag) + r"\\s*(.*?)\\s*$",
                javadoc,
                re.MULTILINE,
            )
            if match is None or not match.group(1).strip():
                missing_tags.append(tag)
            else:
                metadata_values[tag] = match.group(1).strip()
        if missing_tags:
            findings.append({
                "path": relative,
                "line": (javadoc_start or index) + 1,
                "rule": "missing-type-metadata",
                "message": "Missing type Javadoc tags or values: " + ", ".join("@" + tag for tag in missing_tags) + ".",
            })
        invalid_metadata: list[str] = []
        for tag, value in metadata_values.items():
            if PLACEHOLDER_VALUE.search(value):
                invalid_metadata.append(tag + " contains a placeholder")
            elif tag == "CreateTime" and not CREATE_TIME_FORMAT.fullmatch(value):
                invalid_metadata.append("CreateTime must use yyyy/M/d HH:mm")
        if invalid_metadata:
            findings.append({
                "path": relative,
                "line": (javadoc_start or index) + 1,
                "rule": "invalid-type-metadata",
                "message": "; ".join(invalid_metadata) + ".",
            })

    return findings


def changed_java_files(base: str, root: Path = ROOT) -> list[Path]:
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "--diff-filter=ACMRT", f"{base}...HEAD"],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise RuntimeError(f"Unable to list changed files against base {base}: {error}") from error

    paths: list[Path] = []
    for name in result.stdout.splitlines():
        path = root / name
        if path.is_file() and is_java_source(path):
            paths.append(path)
    return sorted(set(paths))


def audit(files: Iterable[Path], require_type_metadata: bool) -> dict[str, object]:
    file_list = list(files)
    findings = [
        finding
        for path in file_list
        for finding in inspect_source(path, require_type_metadata=require_type_metadata)
    ]
    by_rule = Counter(str(finding["rule"]) for finding in findings)
    return {
        "file_count": len(file_list),
        "finding_count": len(findings),
        "findings_by_rule": dict(sorted(by_rule.items())),
        "findings": findings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--audit", action="store_true", help="Report existing debt without failing on findings.")
    mode.add_argument("--changed", action="store_true", help="Strictly validate changed Java files against a Git base.")
    parser.add_argument("--base", help="Base commit SHA or ref for --changed.")
    parser.add_argument("--report", type=Path, help="Write a JSON audit report.")
    args = parser.parse_args()

    if args.changed:
        if not args.base:
            parser.error("--changed requires --base.")
        try:
            files = changed_java_files(args.base)
        except RuntimeError as error:
            print(str(error), file=sys.stderr)
            return 2
        result = audit(files, require_type_metadata=True)
        print(f"Strict Java style check: {result['file_count']} changed Java files, {result['finding_count']} finding(s).")
        for finding in result["findings"]:
            print(f"{finding['path']}:{finding['line']}: {finding['rule']}: {finding['message']}")
        exit_code = 1 if result["finding_count"] else 0
    else:
        files = discover_java_files()
        result = audit(files, require_type_metadata=False)
        print(
            f"Java style audit: {result['file_count']} files, {result['finding_count']} finding(s); "
            f"this is a non-blocking baseline audit."
        )
        for rule, count in result["findings_by_rule"].items():
            print(f"  {rule}: {count}")
        exit_code = 0

    if args.report:
        report = args.report if args.report.is_absolute() else ROOT / args.report
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Audit report written: {report.relative_to(ROOT) if report.is_relative_to(ROOT) else report}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
