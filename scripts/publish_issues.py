#!/usr/bin/env python3
"""Prepare or (only on explicit --apply) create planned GitHub issues via gh CLI.

This tool does not create a repository. Running without --apply performs no network writes.
"""
import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT = "Ryan-Guizhou/otryx-flow"


def gh(args, capture=True):
    return subprocess.run(["gh", *args], check=True, text=True,
                          capture_output=capture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=DEFAULT)
    parser.add_argument("--apply", action="store_true", help="Create missing Issues on GitHub")
    ns = parser.parse_args()
    issues = json.loads((ROOT / ".github" / "issue-plan.json").read_text(encoding="utf-8"))
    print(f"OTRYX Flow Issue plan: {len(issues)} items; repo={ns.repo}")
    if not ns.apply:
        for i in issues:
            print("DRY-RUN", i["id"], i["title"])
        print("No network write performed. Use --apply after creating/accessing the target repo.")
        return
    try:
        gh(["repo", "view", ns.repo, "--json", "nameWithOwner"])
        existing = json.loads(gh(["issue", "list", "--repo", ns.repo, "--state", "all",
                                  "--limit", "500", "--json", "title"]).stdout)
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        raise SystemExit(f"gh unavailable/not authenticated or repository missing: {exc}")
    existing_titles = [x["title"] for x in existing]
    created = 0
    for issue in issues:
        marker = f"[{issue['id']}]"
        if any(marker in title for title in existing_titles):
            print("SKIP existing", marker)
            continue
        try:
            out = gh(["issue", "create", "--repo", ns.repo,
                      "--title", issue["title"], "--body", issue["body"]])
        except subprocess.CalledProcessError as exc:
            raise SystemExit(f"Failed at {issue['id']}; created so far={created}: {exc}")
        print("CREATED", issue["id"], out.stdout.strip())
        created += 1
    print(f"Created {created}, skipped {len(issues) - created} planned issues")


if __name__ == "__main__":
    main()
