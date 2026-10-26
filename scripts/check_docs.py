#!/usr/bin/env python3
"""OTRYX Flow M0 docs quality gate: links, bilingual markers and traceability."""
from pathlib import Path
from urllib.parse import unquote
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    'README.md', 'README.en-US.md', 'AGENTS.md', 'docs/index.md',
    'docs/01-product/requirements-prd.md',
    'docs/01-product/non-functional-requirements.md',
    'docs/02-architecture/architecture-overview.md',
    'docs/03-design/consistency-and-recovery.md',
    'docs/04-api/rpc-contracts.md',
    'docs/05-frontend/workflow-editor.md',
    'docs/06-testing/acceptance-plan.md',
    'docs/06-testing/test-cases.md',
    'docs/08-roadmap/issue-backlog.md',
]
ERRORS = []

for relative in REQUIRED:
    if not (ROOT / relative).is_file():
        ERRORS.append('Missing required file: ' + relative)

all_md = sorted(ROOT.rglob('*.md'))
mermaid_count = 0
if len(all_md) < 25:
    ERRORS.append('Expected a substantive multi-document baseline; found %s MD files' % len(all_md))

for file in all_md:
    rel = file.relative_to(ROOT)
    source = file.read_text(encoding='utf-8')
    if source.count('```') % 2:
        ERRORS.append(f'Unbalanced fenced code block: {rel}')
    # Structural validation only. Full rendering in a browser/mermaid-cli is a separate gate.
    for match in re.finditer(r'```mermaid\s*\n([\s\S]*?)\n```', source):
        mermaid_count += 1
        first = next((x.strip() for x in match.group(1).splitlines() if x.strip()), '')
        if not first.startswith(('flowchart ', 'graph ', 'sequenceDiagram',
                                 'stateDiagram', 'erDiagram', 'classDiagram')):
            ERRORS.append(f'Unsupported/empty Mermaid declaration: {rel}: {first}')
    # Exclude fenced snippets to avoid treating example Markdown as doc links.
    prose = re.sub(r'```[\s\S]*?```', '', source)
    for raw in re.findall(r'(?<!!)\[[^\]]+\]\(([^)]+)\)', prose):
        target = raw.split()[0].strip('<>')
        if target.startswith(('http://', 'https://', 'mailto:', '#', 'data:')):
            continue
        path = unquote(target.split('#')[0].split('?')[0])
        if not path:
            continue
        dest = (file.parent / path).resolve()
        if not dest.is_file() or ROOT not in dest.parents:
            ERRORS.append(f'Broken relative link in {rel}: {raw}')

marker = r'<!-- doc-section:([a-z0-9-]+) -->'
zh = re.findall(marker, (ROOT / 'README.md').read_text(encoding='utf-8'))
en = re.findall(marker, (ROOT / 'README.en-US.md').read_text(encoding='utf-8'))
if zh != en or not zh:
    ERRORS.append('Chinese/English README section marker order differs or is empty')

prd = (ROOT / 'docs/01-product/requirements-prd.md').read_text(encoding='utf-8')
nfr = (ROOT / 'docs/01-product/non-functional-requirements.md').read_text(encoding='utf-8')
accept = (ROOT / 'docs/06-testing/acceptance-plan.md').read_text(encoding='utf-8')
cases = (ROOT / 'docs/06-testing/test-cases.md').read_text(encoding='utf-8')
fr_ids = set(re.findall(r'^\|\s*(FR-\d{3})\s*\|', prd, flags=re.M))
nfr_ids = set(re.findall(r'^\|\s*(NFR-\d{3})\s*\|', nfr, flags=re.M))
at_rows = [r for r in accept.splitlines() if re.match(r'^\|\s*AT-\d{2}\s*\|', r)]
at_ids = set(re.findall(r'^\|\s*(AT-\d{2})\s*\|', accept, flags=re.M))
if len(fr_ids) < 25 or len(nfr_ids) < 10 or len(at_ids) < 16:
    ERRORS.append('Too few FR/NFR/AT identifiers to enforce the planned baseline')
for rid in sorted(fr_ids | nfr_ids):
    if not any(rid in r for r in at_rows):
        ERRORS.append(f'Unmapped requirement in acceptance plan: {rid}')
for aid in sorted(at_ids):
    if aid not in cases:
        ERRORS.append(f'Acceptance criterion missing from test-cases: {aid}')

backlog = (ROOT / 'docs/08-roadmap/issue-backlog.md').read_text(encoding='utf-8')
issue_ids = set(re.findall(r'^\|\s*(FLOW-\d{3})\s*\|', backlog, flags=re.M))
if len(issue_ids) < 28:
    ERRORS.append('Issue backlog not complete; expected at least 28 issue IDs')

if ERRORS:
    print('FAIL: OTRYX Flow M0 documentation')
    for problem in ERRORS:
        print(' -', problem)
    sys.exit(1)
print('PASS: OTRYX Flow M0 documentation')
print(f'  markdown_files: {len(all_md)}')
print(f'  requirements: {len(fr_ids)} FR, {len(nfr_ids)} NFR')
print(f'  acceptance: {len(at_ids)} AT, all linked to test-cases')
print(f'  github_backlog: {len(issue_ids)} planned issues')
print(f'  README bilingual markers: {len(zh)} matching sections')
print('  relative links and fenced code blocks: checked')
print(f'  Mermaid declarations: {mermaid_count} structurally checked (not rendered)')
print('  Mermaid rendered preview, runtime E2E and deployment: NOT RUN at M0')
