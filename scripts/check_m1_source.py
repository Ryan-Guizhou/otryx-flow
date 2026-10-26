#!/usr/bin/env python3
"""Offline M1 source contract assertions, NOT a Java build or RPC E2E."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent.parent
files={p.relative_to(root).as_posix():p.read_text(encoding='utf-8') for p in root.rglob('*') if p.is_file() and p.suffix in {'.java','.xml','.ts','.vue','.sql','.yml','.yaml'}}
needed=['pom.xml','flow-api/pom.xml','flow-metadata/pom.xml','flow-contracts/pom.xml','flow-scheduler-core/pom.xml',
        'flow-web/src/App.vue','flow-web/src/api.ts','compose.yaml',
        'flow-scheduler/pom.xml','flow-engine/pom.xml','flow-dispatcher/pom.xml','flow-worker-sdk/pom.xml','flow-worker-quickstart/pom.xml', 'flow-scheduler/src/main/resources/db/migration/V1__create_schedule_outbox.sql']
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
# Nacos 3.2.4 is the only active registry. Endpoints are host:port (no URI scheme).
assert 'image: nacos/nacos-server:v3.2.4' in compose
assert 'nacos-bootstrap:' in compose and 'service_completed_successfully' in compose
assert compose.count('OTRYX_REGISTRY_ENDPOINTS: nacos:8848') == 7
assert 'etcd:' not in compose and 'quay.io/coreos/etcd' not in compose and 'etcd_data:' not in compose
assert 'NACOS_AUTH_ENABLE: "false"' in compose and 'internal: true' in compose
assert 'NacosV2MetadataBootstrap' in compose
for module in ('flow-api','flow-metadata','flow-scheduler','flow-engine','flow-dispatcher'):
    assert 'otryx-registry-nacos' in files[module + '/pom.xml']
    assert 'otryx-registry-etcd' not in files[module + '/pom.xml']
    cfg=files[module + '/src/main/resources/application.yml']
    assert 'type: nacos' in cfg and 'namespace: public' in cfg
    assert 'OTRYX_REGISTRY_ENDPOINTS:127.0.0.1:8848' in cfg

for app_file in ('flow-api/src/main/resources/application.yml', 'flow-metadata/src/main/resources/application.yml'):
    assert 'OTRYX_REGISTRY_ENDPOINTS:127.0.0.1:8848' in files[app_file], app_file
assert 'networks: [internal, ingress]' in compose and 'ingress: {}' in compose, 'Host web ingress must be separate from internal RPC network'
assert 'NETWORK_MTLS' not in files['flow-api/src/main/resources/application.yml'], 'M1 is development only'
assert 'NETWORK_MTLS' not in files['flow-metadata/src/main/resources/application.yml'], 'M1 is development only'
assert 'startSession' not in src, 'Do not substitute application authentication with implicit sessions'
for f in ['pom.xml','flow-api/pom.xml','flow-metadata/pom.xml','flow-contracts/pom.xml','flow-scheduler-core/pom.xml','flow-scheduler/pom.xml','flow-engine/pom.xml','flow-dispatcher/pom.xml','flow-worker-sdk/pom.xml','flow-worker-quickstart/pom.xml']:
    ET.fromstring(files[f])
for f in ['scripts/test_frontend_api.mjs', 'scripts/test_e2e_harness_offline.mjs',
          '.github/workflows/m1-integration.yml']:
    assert (root/f).is_file(), f'Missing verification or manual workflow: {f}'
assert 'workflow_dispatch' in (root/'.github/workflows/m1-integration.yml').read_text(encoding='utf-8')
assert (root/'scripts/start_dev_compose.sh').is_file(), 'Use bounded sequential OTRYX RPC startup in developer CI'
assert 'nacos-bootstrap' in (root/'scripts/start_dev_compose.sh').read_text(encoding='utf-8')
assert 'bash scripts/start_dev_compose.sh' in (root/'.github/workflows/m1-integration.yml').read_text(encoding='utf-8')
assert 'flow-scheduler-core' in files['pom.xml'], 'Cron algorithm must be reusable by future scheduler'
cron=files['flow-scheduler-core/src/main/java/com/peachsoft/otryx/flow/scheduler/core/CronScheduleCalculator.java']
assert 'CronExpression.parse' in cron and 'getValidOffsets' in cron and 'MAX_COUNT = 20' in cron
assert 'JdbcTemplate' not in cron and 'OtryxRpcClient' not in cron, 'Cron preview is a pure calculation'
assert 'flow-dispatcher/**' in (root/'.github/workflows/m1-quality.yml').read_text(encoding='utf-8')
assert 'flow-scheduler-core/**' in (root/'.github/workflows/m1-quality.yml').read_text(encoding='utf-8')
assert 'flow-worker-demo:' in compose and 'profiles: [worker-demo]' in compose
assert 'COPY flow-worker-quickstart ./flow-worker-quickstart' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8')
assert 'COPY flow-worker-sdk ./flow-worker-sdk' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8')
assert (root/'scripts/test_m2_worker_e2e.sh').is_file()
assert 'flow-dispatcher:' in compose and 'flow-dispatcher-replica:' in compose
assert 'flow-engine:' in compose and 'flow-scheduler-replica:' in compose and 'flow-scheduler:' in compose
assert 'UNIQUE (schedule_id, schedule_revision, planned_fire_at_utc, origin)' in src
assert 'FOR UPDATE SKIP LOCKED' in src and 'ISOLATION_READ_COMMITTED' in src
assert 'flow_schedule_outbox' in src
assert 'COPY flow-dispatcher ./flow-dispatcher' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8')
assert 'flow_dispatch_task' in src and 'flow_dispatch_attempt' in src
assert 'lease_epoch' in src and 'FOR UPDATE SKIP LOCKED' in src
assert 'flow_engine_dispatch_outbox' in src
assert 'COPY flow-engine ./flow-engine' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8')
assert 'flow_engine_inbox' in src and 'flow_workflow_run' in src
assert 'flow_schedule_outbox' in src and 'claim_token' in src
assert 'COPY flow-scheduler ./flow-scheduler' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8')
assert 'COPY flow-scheduler-core ./flow-scheduler-core' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8'), 'Include new Maven modules in Docker build'
print(f'PASS: M1 static contracts ({len(files)} source/config files)')
print('NOTE: This does not run Maven, MySQL, network Nacos, OTRYX RPC, npm or a browser.')
