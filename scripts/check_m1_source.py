#!/usr/bin/env python3
"""Offline M1 source contract assertions, NOT a Java build or RPC E2E."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent.parent
files={p.relative_to(root).as_posix():p.read_text(encoding='utf-8') for p in root.rglob('*') if p.is_file() and p.suffix in {'.java','.xml','.ts','.vue','.sql','.yml','.yaml'}}
needed=['pom.xml','flow-api/pom.xml','flow-metadata/pom.xml','flow-contracts/pom.xml','flow-scheduler-core/pom.xml',
        'flow-web/src/App.vue','flow-web/src/api.ts','flow-web/src/DagRunPanel.vue','compose.yaml',
        'flow-scheduler/pom.xml','flow-engine/pom.xml','flow-dispatcher/pom.xml','flow-worker-sdk/pom.xml','flow-worker-quickstart/pom.xml', 'flow-scheduler/src/main/resources/db/migration/V1__create_schedule_outbox.sql']
for f in needed:
    assert f in files, f'Missing {f}'
for f in files:
    if f.endswith('.java'):
        assert re.search(r'^package\s+com\.peachsoft\.otryx\.flow\.',files[f],re.M), f
        # Brace balance must ignore Java strings, text blocks, chars and comments.
        # DAG fixtures legitimately contain JSON object literals with unmatched braces.
        without_literals = re.sub(r'"""[\s\S]*?"""', '""', files[f])
        without_literals = re.sub(r'"(?:\\.|[^"\\])*"', '""', without_literals)
        without_literals = re.sub(r"'(?:\\.|[^'\\])*'", "''", without_literals)
        without_literals = re.sub(r'/\*[\s\S]*?\*/|//[^\n]*', '', without_literals)
        assert without_literals.count('{') == without_literals.count('}'), f
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
assert compose.count('OTRYX_REGISTRY_ENDPOINTS: nacos:8848') == 8
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
worker_src = files['flow-worker-sdk/src/main/java/com/peachsoft/otryx/flow/worker/sdk/WorkerRuntime.java']
assert '.daemon(false).unstarted(runnable)' in worker_src, 'Non-web Worker must keep non-daemon control threads alive'
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
# The final Dispatcher outcome is persisted with the Worker Attempt and reconciled idempotently.
assert 'flow_dispatch_outcome_outbox' in src and 'flow_engine_outcome_inbox' in src
assert 'stageOutcome' in src and 'acknowledgeTaskOutcome' in src
assert 'FLOW_E2E_M2_FINAL' in (root/'.github/workflows/m1-integration.yml').read_text(encoding='utf-8')
assert (root/'scripts/test_m2_final_e2e.sh').is_file()
assert 'flow_schedule_outbox' in src and 'claim_token' in src
assert 'COPY flow-scheduler ./flow-scheduler' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8')
assert 'COPY flow-scheduler-core ./flow-scheduler-core' in (root/'deploy/Dockerfile.backend').read_text(encoding='utf-8'), 'Include new Maven modules in Docker build'
# FLOW-015 workflow correctness contracts; real build and Nacos E2E run independently.
assert 'flow-metadata/src/main/resources/db/migration/V3__workflow_definition.sql' in files
assert 'flow_workflow_revision' in src and 'graph_sha256' in src
assert 'WorkflowDagValidator.validate' in files['flow-metadata/src/main/java/com/peachsoft/otryx/flow/metadata/workflow/WorkflowDefinitionGraph.java']
assert "hasRole('ADMIN')" in files['flow-api/src/main/java/com/peachsoft/otryx/flow/api/workflow/WorkflowController.java']
assert (root/'scripts/test_m3_workflow_e2e.sh').is_file()
assert 'FLOW_E2E_M3_WORKFLOWS' in (root/'.github/workflows/m1-integration.yml').read_text(encoding='utf-8')
assert 'bash scripts/test_m3_workflow_e2e.sh' in (root/'scripts/test_m1_e2e.sh').read_text(encoding='utf-8')
# FLOW-016 node-scoped durable DAG execution is a separate path from legacy M2 runs.
assert 'flow-engine/src/main/resources/db/migration/V4__dag_node_execution.sql' in files
assert 'flow_dag_outcome_inbox' in src and 'flow_dag_dispatch_outbox' in src
assert 'node_run_id VARCHAR(36) NOT NULL PRIMARY KEY' in src
assert 'DagDependencyPlanner.advance' in files['flow-engine/src/main/java/com/peachsoft/otryx/flow/engine/dag/DagRunCoordinator.java']
assert '@OtryxRpcReference' in files['flow-engine/src/main/java/com/peachsoft/otryx/flow/engine/dag/DagRunRpcService.java']
assert 'FLOW_E2E_M3_DAG' in (root/'.github/workflows/m1-integration.yml').read_text(encoding='utf-8')
assert 'bash scripts/test_m3_dag_e2e.sh' in (root/'scripts/test_m1_e2e.sh').read_text(encoding='utf-8')
assert (root/'scripts/test_m3_dag_e2e.sh').is_file()
# FLOW-017: only Engine may mutate DAG Run control state; Dispatcher owns durable cancellation fence.
assert 'flow-engine/src/main/resources/db/migration/V5__dag_cancel_retry_control.sql' in files
assert 'flow_dag_cancel_outbox' in src and 'flow_dag_control_audit' in src
assert 'run_version BIGINT NOT NULL DEFAULT 1' in src
assert 'cancelDagTask' in files['flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/DispatcherRpc.java']
assert 'FOR UPDATE' in files['flow-engine/src/main/java/com/peachsoft/otryx/flow/engine/dag/DagControlService.java']
assert 'FLOW_E2E_M3_CONTROL' in (root/'.github/workflows/m1-integration.yml').read_text(encoding='utf-8')
assert 'bash scripts/test_m3_control_e2e.sh' in (root/'scripts/test_m1_e2e.sh').read_text(encoding='utf-8')
assert (root/'scripts/test_m3_control_e2e.sh').is_file()
# FLOW-018: Vue Flow canvas stays separate from the persisted schema-v1 graph.
assert 'flow-web/src/WorkflowEditor.vue' in files
assert 'validateWorkflowGraph' in files['flow-web/src/workflowGraph.ts']
assert 'serializeWorkflowGraph' in files['flow-web/src/workflowGraph.ts']
assert 'startDagRun' in files['flow-web/src/WorkflowEditor.vue']
assert 'localStorage' in files['flow-web/src/WorkflowEditor.vue']
assert '@vue-flow/core' in (root/'flow-web/package.json').read_text(encoding='utf-8')
assert 'WorkflowEditor' in files['flow-web/src/App.vue']
# FLOW-019 bounded, redacted Dispatcher logs and owner-scoped DAG run details.
assert 'flow-dispatcher/src/main/resources/db/migration/V3__bounded_execution_logs.sql' in files
assert 'flow_dispatch_log' in src and 'uq_dispatch_log_sequence' in src
assert 'appendLogs' in files['flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/DispatcherRpc.java']
assert 'queryLogs' in files['flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/DispatcherRpc.java']
assert 'SENSITIVE_ASSIGNMENT' in files['flow-dispatcher/src/main/java/com/peachsoft/otryx/flow/dispatcher/DispatcherRpcService.java']
assert 'ArrayBlockingQueue<PendingLog>' in files['flow-worker-sdk/src/main/java/com/peachsoft/otryx/flow/worker/sdk/WorkerRuntime.java']
assert 'getDagRunLogs' in files['flow-web/src/api.ts']
assert 'cancelDagRun' in files['flow-web/src/DagRunPanel.vue']
assert 'flow-dispatcher/src/main/java/com/peachsoft/otryx/flow/dispatcher/DispatchLogRetentionTask.java' in files
assert 'List<SanitizedLog> sanitized' in files['flow-dispatcher/src/main/java/com/peachsoft/otryx/flow/dispatcher/DispatcherRpcService.java']
assert 'MAX_LOG_QUERY_NODES = 300' in files['flow-dispatcher/src/main/java/com/peachsoft/otryx/flow/dispatcher/DispatcherRpcService.java']
assert 'cursor.length() > 512' in files['flow-dispatcher/src/main/java/com/peachsoft/otryx/flow/dispatcher/DispatcherRpcService.java']
assert 'cursor.length() > 512' in files['flow-api/src/main/java/com/peachsoft/otryx/flow/api/run/DagRunController.java']
assert 'invalidLogBatchIsRejectedBeforeAnyRecordIsWritten' in files['flow-dispatcher/src/test/java/com/peachsoft/otryx/flow/dispatcher/DispatcherRpcServiceTest.java']
assert 'logsRequireRunOwnershipAndDelegateOnlyThatRunsNodeIds' in files['flow-api/src/test/java/com/peachsoft/otryx/flow/api/run/DagRunControllerTest.java']
assert 'handlerLogsAreFlushedAsynchronouslyThroughTheBoundedQueue' in files['flow-worker-sdk/src/test/java/com/peachsoft/otryx/flow/worker/sdk/WorkerRuntimeTest.java']
assert 'getWorkflowRevision' in files['flow-web/src/DagRunPanel.vue']
assert 'pagedRuns' in files['flow-web/src/DagRunPanel.vue']
assert 'Retry same request' in files['flow-web/src/DagRunPanel.vue']
assert 'logs?limit=100' in (root/'scripts/test_m3_dag_e2e.sh').read_text(encoding='utf-8')
assert 'Handler execution started' in (root/'scripts/test_m3_dag_e2e.sh').read_text(encoding='utf-8')
print(f'PASS: M1/M2/M3 static contracts ({len(files)} source/config files)')
print('NOTE: This does not run Maven, MySQL, network Nacos, OTRYX RPC, npm or a browser.')
