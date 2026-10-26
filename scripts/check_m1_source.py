#!/usr/bin/env python3
"""Offline M1 source contract assertions, NOT a Java build or RPC E2E."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent.parent
files={p.relative_to(root).as_posix():p.read_text(encoding='utf-8') for p in root.rglob('*') if p.is_file() and p.suffix in {'.java','.xml','.ts','.vue','.sql','.yml','.yaml'}}
needed=['pom.xml','flow-api/pom.xml','flow-metadata/pom.xml','flow-contracts/pom.xml',
        'flow-web/src/App.vue','flow-web/src/api.ts','compose.yaml']
for f in needed:
    assert f in files, f'Missing {f}'
for f in files:
    if f.endswith('.java'):
        assert re.search(r'^package\s+com\.peachsoft\.otryx\.flow\.',files[f],re.M), f
        assert files[f].count('{') == files[f].count('}'), f
src='\n'.join(files.values())
for contract in ['@OtryxRpcContract','@OtryxRpcService','@OtryxRpcReference','@OtryxRpcIdempotent']:
    assert contract in src, contract
assert 'CREATE TABLE flow_project' in src and 'CREATE TABLE flow_user' in src
assert 'CookieCsrfTokenRepository' in src and 'BCryptPasswordEncoder' in src
assert 'Idempotency-Key' in src
security=files['flow-api/src/main/java/com/peachsoft/otryx/flow/api/security/FlowSecurityConfiguration.java']
assert 'HttpStatusEntryPoint' in security and 'HttpStatus.UNAUTHORIZED' in security, 'Unauthenticated REST must send 401'
assert 'new CookieCsrfTokenRepository()' in security and 'withHttpOnlyFalse' not in security, 'CSRF cookie must be HTTP-only'
assert "hasRole('ADMIN')" in files['flow-api/src/main/java/com/peachsoft/otryx/flow/api/project/ProjectController.java']
assert "@OtryxRpcIdempotent" in files['flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/ProjectRpc.java']
assert 'UNIQUE (owner_username, request_key)' in src, 'Idempotent writes must be backed by a unique index'
compose=files['compose.yaml']
assert '127.0.0.1:8088:80' in compose and 'internal: true' in compose, 'Developer environment should not expose untrusted RPC network'
assert 'NETWORK_MTLS' not in files['flow-api/src/main/resources/application.yml'], 'M1 is development only'
assert 'NETWORK_MTLS' not in files['flow-metadata/src/main/resources/application.yml'], 'M1 is development only'
assert 'startSession' not in src, 'Do not substitute application authentication with implicit sessions'
for f in ['pom.xml','flow-api/pom.xml','flow-metadata/pom.xml','flow-contracts/pom.xml']:
    ET.fromstring(files[f])
for f in ['scripts/test_frontend_api.mjs', 'scripts/test_e2e_harness_offline.mjs',
          '.github/workflows/m1-integration.yml']:
    assert (root/f).is_file(), f'Missing verification or manual workflow: {f}'
assert 'workflow_dispatch' in (root/'.github/workflows/m1-integration.yml').read_text(encoding='utf-8')
print(f'PASS: M1 static contracts ({len(files)} source/config files)')
print('NOTE: This does not run Maven, MySQL, network Etcd, OTRYX RPC, npm or a browser.')
