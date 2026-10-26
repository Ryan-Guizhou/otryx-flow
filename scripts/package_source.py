#!/usr/bin/env python3
"""Package M1 source and docs with per-file SHA256, excluding secrets and build outputs."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT.parent / 'otryx-flow-m1-hardened.zip'
SKIP_DIRS = {'.git', '.idea', '.vscode', 'node_modules', 'target', 'dist', '__pycache__', '.vite'}
SKIP_NAMES = {'manifest.json', '.env', 'id_rsa', 'id_ed25519'}
SECRET_SUFFIXES = {'.pem', '.p12', '.key', '.jks'}

files = []
for path in sorted(ROOT.rglob('*')):
    if not path.is_file():
        continue
    rel = path.relative_to(ROOT)
    if any(part in SKIP_DIRS for part in rel.parts) or rel.name in SKIP_NAMES:
        continue
    if rel.name.startswith('.env.') and rel.name != '.env.example':
        continue
    if path.suffix in SECRET_SUFFIXES:
        continue
    files.append({'path': rel.as_posix(), 'bytes': path.stat().st_size,
                  'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})

manifest = {
    'name': 'otryx-flow', 'milestone': 'M1-hardened-source-candidate',
    'date': '2026-10-26',
    'otryx_rpc_commit': '706b6506e49e1ade70de9c6c64c844fdd4631e17',
    'verified': {
        'docs_traceability': 'PASS', 'm1_static_source_contracts': 'PASS',
        'node_frontend_api_tests': 'PASS: 3',
        'e2e_shell_harness_with_fake_http': 'PASS: not real RPC',
        'java21_dto_javac': 'PASS', 'typescript_api_tsc': 'PASS',
        'yaml_and_shell_syntax': 'PASS',
    },
    'not_verified': ['full Maven build', 'JUnit execution', 'Vue package build',
                     'Docker Compose runtime', 'actual two-JVM OTRYX RPC',
                     'public HTTPS deployment', 'GitHub CI/push'],
    'files': files,
}
(ROOT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(OUTPUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for entry in files:
        archive.write(ROOT / entry['path'], arcname='otryx-flow/' + entry['path'])
    archive.write(ROOT / 'manifest.json', arcname='otryx-flow/manifest.json')
with zipfile.ZipFile(OUTPUT, 'r') as archive:
    assert archive.testzip() is None
    assert len(archive.namelist()) == len(files) + 1
    embedded = json.loads(archive.read('otryx-flow/manifest.json'))
    for entry in embedded['files']:
        digest = hashlib.sha256(archive.read('otryx-flow/' + entry['path'])).hexdigest()
        assert digest == entry['sha256'], f'checksum mismatch: {entry["path"]}'
print(f'PASS: {OUTPUT} ({len(files)+1} entries, {OUTPUT.stat().st_size} bytes)')
