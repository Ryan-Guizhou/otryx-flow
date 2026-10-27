/** Offline HTTP adapter verification using Node 22; this is NOT a Vue/browser or RPC integration test. */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  refreshCsrf, currentUser, login, logout, listProjects, createProject
} from '../flow-web/src/api.ts'
import { validateWorkflowGraph, serializeWorkflowGraph, defaultWorkflowGraph, computeDefaultLayout } from '../flow-web/src/workflowGraph.ts'

const originalFetch = globalThis.fetch
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' }
})

test('CSRF bootstrap, login, project request, idempotent replay and logout share an HTTP session', async () => {
  const requests = []
  let csrfRevision = 0
  let projects = []
  globalThis.fetch = async (input, init = {}) => {
    const request = { path: String(input), method: init.method || 'GET',
      headers: new Headers(init.headers), body: init.body, credentials: init.credentials }
    requests.push(request)
    assert.equal(request.credentials, 'same-origin')
    if (request.path === '/api/v1/auth/csrf') return json({ token: `csrf-${++csrfRevision}` })
    if (request.path === '/api/v1/auth/me') return json({ username: 'admin', role: 'ADMIN' })
    if (request.path === '/api/v1/auth/login') {
      assert.equal(request.headers.get('X-XSRF-TOKEN'), 'csrf-1')
      assert.equal(request.headers.get('Content-Type'), 'application/json')
      assert.deepEqual(JSON.parse(request.body), { username: 'admin', password: 'secret' })
      return json({ username: 'admin', role: 'ADMIN' })
    }
    if (request.path === '/api/v1/projects' && request.method === 'GET') return json(projects)
    if (request.path === '/api/v1/projects' && request.method === 'POST') {
      assert.equal(request.headers.get('X-XSRF-TOKEN'), 'csrf-2')
      assert.equal(request.headers.get('Idempotency-Key'), 'valid-key-1234567890')
      const name = JSON.parse(request.body).name
      if (!projects.length) projects = [{ id: 'id-123', name, createdAt: '2026-10-26T00:00:00Z' }]
      return json(projects[0], 201)
    }
    if (request.path === '/api/v1/auth/logout') {
      assert.equal(request.headers.get('X-XSRF-TOKEN'), 'csrf-2')
      return new Response(null, { status: 204 })
    }
    throw new Error(`Unexpected URL ${request.path}`)
  }
  try {
    await refreshCsrf()
    assert.deepEqual(await login('admin', 'secret'), { username: 'admin', role: 'ADMIN' })
    assert.deepEqual(await currentUser(), { username: 'admin', role: 'ADMIN' })
    assert.deepEqual(await listProjects(), [])
    const first = await createProject('M1 test', 'valid-key-1234567890')
    const again = await createProject('M1 test', 'valid-key-1234567890')
    assert.deepEqual(first, again)
    assert.equal((await listProjects()).length, 1)
    await logout()
    assert.equal(csrfRevision, 3)
    assert.ok(requests.every(x => x.credentials === 'same-origin'))
  } finally { globalThis.fetch = originalFetch }
})

test('HTTP adapter surfaces typed API errors, and never silently treats 503 as success', async () => {
  globalThis.fetch = async () => json({ code: 'RPC_UNAVAILABLE' }, 503)
  try {
    await assert.rejects(listProjects(), err => {
      assert.equal(err.status, 503)
      assert.equal(err.code, 'RPC_UNAVAILABLE')
      return true
    })
  } finally { globalThis.fetch = originalFetch }
})

test('HTTP adapter uses HTTP status when response is not a JSON error', async () => {
  globalThis.fetch = async () => new Response('Unavailable', { status: 502 })
  try {
    await assert.rejects(listProjects(), err => {
      assert.equal(err.status, 502)
      assert.equal(err.message, 'HTTP_502')
      return true
    })
  } finally { globalThis.fetch = originalFetch }
})

test('M2 job draft and publication client send stable request keys and CAS versions', async () => {
  const { listJobs, createJobDraft, publishJob, saveJobDraft } = await import('../flow-web/src/api.ts')
  const project = '00000000-0000-4000-8000-000000000001'
  const job = { id: '00000000-0000-4000-8000-000000000002', projectId: project,
    name: 'Demo', handlerKey: 'safe.echo', parametersJson: '{}', timeoutSeconds: 30,
    maxAttempts: 2, draftVersion: 1, publishedRevision: 0, lastPublishedDraftVersion: 0,
    updatedAt: '2026-10-26T00:00:00Z' }
  const input = { name: job.name, handlerKey: job.handlerKey, parametersJson: '{}',
    timeoutSeconds: 30, maxAttempts: 2 }
  const key = 'valid-job-request-key-100001'
  const calls = []
  globalThis.fetch = async (path, init = {}) => {
    const record = { path: String(path), method: init.method || 'GET',
      body: init.body, headers: new Headers(init.headers), credentials: init.credentials }
    calls.push(record)
    if (record.method === 'GET') return json([job])
    return json(job, record.method === 'POST' ? 201 : 200)
  }
  try {
    assert.deepEqual(await listJobs(project), [job])
    assert.deepEqual(await createJobDraft(project, input, key), job)
    assert.deepEqual(await saveJobDraft(project, job, input), job)
    assert.deepEqual(await publishJob(project, job, key), job)
    assert.equal(calls[1].headers.get('Idempotency-Key'), key)
    assert.equal(calls[3].headers.get('Idempotency-Key'), key)
    assert.equal(JSON.parse(calls[2].body).expectedDraftVersion, 1)
    assert.equal(JSON.parse(calls[3].body).expectedDraftVersion, 1)
    assert.ok(calls.every(x => x.credentials === 'same-origin'))
  } finally { globalThis.fetch = originalFetch }
})

test('Cron preview sends an explicit UTC instant and receives bounded occurrence list', async () => {
  const { previewCron } = await import('../flow-web/src/api.ts')
  const calls = []
  globalThis.fetch = async (path, init = {}) => {
    const request = { path: String(path), headers: new Headers(init.headers),
      method: init.method, body: JSON.parse(init.body), credentials: init.credentials }
    calls.push(request)
    return json({ expression: request.body.expression, timeZone: request.body.timeZone,
      fromUtc: request.body.fromUtc, occurrences: [{ utc: '2026-10-26T12:00:00Z',
        local: '2026-10-26T20:00:00', offset: '+08:00' }], overlapOccurrencesSkipped: 0 })
  }
  try {
    const result = await previewCron('0 0 20 * * *', 'Asia/Shanghai', '2026-10-26T00:00:00Z', 1)
    assert.equal(result.occurrences[0].offset, '+08:00')
    assert.equal(calls[0].method, 'POST')
    assert.equal(calls[0].path, '/api/v1/schedules/preview')
    assert.equal(calls[0].body.limit, 1)
    assert.equal(calls[0].credentials, 'same-origin')
    assert.ok(calls[0].headers.get('X-XSRF-TOKEN'))
  } finally { globalThis.fetch = originalFetch }
})

test('M2 Scheduler API client sends immutable revision and version CAS', async () => {
  const { createSchedule, listSchedules, listScheduleTriggers, toggleSchedule } =
    await import('../flow-web/src/api.ts')
  const project = '00000000-0000-4000-8000-000000000001'
  const job = '00000000-0000-4000-8000-000000000002'
  const schedule = '00000000-0000-4000-8000-000000000003'
  const calls = []
  globalThis.fetch = async (path, init = {}) => {
    const request = { path: String(path), method: init.method || 'GET',
      body: init.body && JSON.parse(init.body), headers: new Headers(init.headers), credentials: init.credentials }
    calls.push(request)
    if (request.method === 'GET') return json([])
    return json({ id: schedule, jobId: job, jobRevision: 2, version: 1, enabled: true },
      request.path.endsWith('/schedules') ? 201 : 200)
  }
  try {
    const input = { jobId: job, jobRevision: 2, expression: '*/5 * * * * *',
      timeZone: 'Etc/UTC', misfirePolicy: 'FIRE_ONCE' }
    assert.equal((await createSchedule(project, input, 'valid-schedule-key-12345678')).id, schedule)
    await listSchedules(project)
    await listScheduleTriggers(project, schedule)
    await toggleSchedule(project, schedule, 1, false)
    assert.equal(calls[0].method, 'POST')
    assert.equal(calls[0].headers.get('Idempotency-Key'), 'valid-schedule-key-12345678')
    assert.equal(calls[0].body.jobRevision, 2)
    assert.equal(calls[3].body.expectedVersion, 1)
    assert.equal(calls[3].body.enabled, false)
    assert.ok(calls.every(c => c.credentials === 'same-origin'))
  } finally { globalThis.fetch = originalFetch }
})

test('Engine Runs API is a read-only, scoped durable-state query', async () => {
  const { listRuns } = await import('../flow-web/src/api.ts')
  const project = '00000000-0000-4000-8000-000000000001'
  const calls = []
  globalThis.fetch = async (path, init = {}) => {
    calls.push({ path: String(path), method: init.method || 'GET', credentials: init.credentials })
    return json([{ id: 'run-1', eventId: 'event-1', scheduleId: 'schedule-1',
      jobRevision: 2, status: 'WAITING_DISPATCH' }])
  }
  try {
    const runs = await listRuns(project)
    assert.equal(runs[0].status, 'WAITING_DISPATCH')
    assert.equal(calls[0].path, `/api/v1/projects/${project}/runs`)
    assert.equal(calls[0].method, 'GET')
    assert.equal(calls[0].credentials, 'same-origin')
  } finally { globalThis.fetch = originalFetch }
})

test('Dispatch queue GET exposes only scoped, read-only task metadata', async () => {
  const { listDispatchTasks } = await import('../flow-web/src/api.ts')
  const project = '00000000-0000-4000-8000-000000000001'
  const calls = []
  globalThis.fetch = async (path, init = {}) => {
    calls.push({ path: String(path), method: init.method || 'GET', credentials: init.credentials })
    return json([{ id: 'task-1', runId: 'run-1', projectId: project, handlerKey: 'safe.echo',
      status: 'READY', attempts: 0, maxAttempts: 2, leaseEpoch: 0 }])
  }
  try {
    const tasks = await listDispatchTasks(project)
    assert.equal(tasks[0].status, 'READY')
    assert.equal(calls[0].path, `/api/v1/projects/${project}/dispatch-tasks`)
    assert.equal(calls[0].method, 'GET')
    assert.equal(calls[0].credentials, 'same-origin')
    assert.equal(Object.hasOwn(tasks[0], 'leaseToken'), false)
  } finally { globalThis.fetch = originalFetch }
})

test('M3 workflow API sends scoped paths, stable keys and draft-version CAS', async () => {
  const { listWorkflows, createWorkflowDraft, saveWorkflowDraft, publishWorkflow,
    getWorkflowRevision } = await import('../flow-web/src/api.ts')
  const project = '00000000-0000-4000-8000-000000000001'
  const workflow = { id: '00000000-0000-4000-8000-000000000004', projectId: project,
    name: 'Graph', graphJson: '{"schemaVersion":1,"nodes":[],"edges":[]}',
    draftVersion: 3, publishedRevision: 1, lastPublishedDraftVersion: 2,
    updatedAt: '2026-10-27T00:00:00Z' }
  const key = 'valid-workflow-key-1234567890'
  const requests = []
  globalThis.fetch = async (path, init = {}) => {
    const request = { path: String(path), method: init.method || 'GET',
      body: init.body && JSON.parse(init.body), headers: new Headers(init.headers),
      credentials: init.credentials }
    requests.push(request)
    if (request.path.endsWith('/revisions/1')) {
      return json({ workflowId: workflow.id, revision: 1, graphSha256: 'a'.repeat(64) })
    }
    if (request.method === 'GET') return json([workflow])
    return json(workflow, request.method === 'POST' ? 201 : 200)
  }
  try {
    assert.deepEqual(await listWorkflows(project), [workflow])
    await createWorkflowDraft(project, { name: 'Graph', graphJson: workflow.graphJson }, key)
    await saveWorkflowDraft(project, workflow, { name: 'Edited', graphJson: workflow.graphJson })
    await publishWorkflow(project, workflow, key)
    assert.equal((await getWorkflowRevision(project, workflow.id, 1)).revision, 1)
    assert.equal(requests[0].path, '/api/v1/projects/' + project + '/workflows')
    assert.equal(requests[1].headers.get('Idempotency-Key'), key)
    assert.equal(requests[2].body.expectedDraftVersion, 3)
    assert.equal(requests[3].body.expectedDraftVersion, 3)
    assert.equal(requests[3].headers.get('Idempotency-Key'), key)
    assert.ok(requests.every(request => request.credentials === 'same-origin'))
    assert.ok(requests.slice(1,4).every(request => request.headers.get('X-XSRF-TOKEN')))
  } finally { globalThis.fetch = originalFetch }
})

test('M3 DAG run API pins published revision with stable idempotency and owner-scoped reads', async () => {
  const { startDagRun, listDagRuns, getDagRun } = await import('../flow-web/src/api.ts')
  const project = '00000000-0000-4000-8000-000000000001'
  const workflow = '00000000-0000-4000-8000-000000000002'
  const runId = '00000000-0000-4000-8000-000000000003'
  const run = { id: runId, projectId: project, workflowId: workflow, workflowRevision: 2,
    graphSha256: 'a'.repeat(64), status: 'RUNNING', createdAt: '2026-10-27T00:00:00Z',
    updatedAt: '2026-10-27T00:00:00Z' }
  const calls = []
  globalThis.fetch = async (path, init = {}) => {
    const current = { path: String(path), method: init.method || 'GET',
      body: init.body && JSON.parse(init.body), credentials: init.credentials,
      headers: new Headers(init.headers) }
    calls.push(current)
    if (current.method === 'POST') return json(run, 201)
    if (current.path.endsWith('/' + runId)) return json({ run, nodes: [
      { id: 'node-id', nodeId: 'A', type: 'TASK', status: 'READY',
        conditionDecision: null, updatedAt: run.createdAt }] })
    return json([run])
  }
  try {
    const key = 'valid-dag-run-key-1234567890'
    assert.equal((await startDagRun(project, { workflowId: workflow,
      workflowRevision: 2, inputJson: '{}' }, key)).id, runId)
    assert.equal((await listDagRuns(project)).length, 1)
    assert.equal((await getDagRun(project, runId)).nodes[0].status, 'READY')
    assert.equal(calls[0].method, 'POST')
    assert.equal(calls[0].headers.get('Idempotency-Key'), key)
    assert.ok(calls[0].headers.get('X-XSRF-TOKEN'))
    assert.equal(calls[0].body.workflowRevision, 2)
    assert.equal(calls[0].path, '/api/v1/projects/' + project + '/workflow-runs')
    assert.ok(calls.every(c => c.credentials === 'same-origin'))
  } finally { globalThis.fetch = originalFetch }
})

test('FLOW-017 browser client preserves control idempotency and optimistic Run version', async () => {
  const { cancelDagRun, retryDagNode, listDagControlHistory } =
    await import('../flow-web/src/api.ts')
  const project = '00000000-0000-4000-8000-000000000001'
  const runId = '00000000-0000-4000-8000-000000000002'
  const nodeId = 'my node/alpha'
  const key = 'dag-control-idempotency-key-123'
  const calls = []
  globalThis.fetch = async (path, init = {}) => {
    const request = { path: String(path), method: init.method || 'GET',
      headers: new Headers(init.headers), body: init.body && JSON.parse(init.body),
      credentials: init.credentials }
    calls.push(request)
    return json(request.method === 'GET' ? [
      { action: 'RETRY', nodeId: 'A', oldVersion: 2, newVersion: 4 }] :
      { code: 'OK', run: { id: runId, status: 'CANCEL_REQUESTED', runVersion: 4 },
        nodeRunId: null, duplicate: false })
  }
  try {
    assert.equal((await cancelDagRun(project, runId, 3, key)).run.runVersion, 4)
    assert.equal((await retryDagNode(project, runId, nodeId, 3, key)).code, 'OK')
    assert.equal((await listDagControlHistory(project, runId)).length, 1)
    assert.equal(calls[0].path, '/api/v1/projects/' + project + '/workflow-runs/' + runId + '/cancel')
    assert.equal(calls[1].path, '/api/v1/projects/' + project + '/workflow-runs/' + runId +
      '/nodes/my%20node%2Falpha/retry')
    assert.ok(calls.slice(0,2).every(c => c.headers.get('Idempotency-Key') === key))
    assert.ok(calls.slice(0,2).every(c => c.headers.get('X-XSRF-TOKEN')))
    assert.ok(calls.slice(0,2).every(c => c.body.expectedRunVersion === 3))
    assert.ok(calls.every(c => c.credentials === 'same-origin'))
    assert.equal(calls[2].method, 'GET')
  } finally { globalThis.fetch = originalFetch }
})


const taskNode = (id, overrides = {}) => ({
  id, type: 'TASK', label: id, handlerKey: 'safe.echo', parameters: {},
  timeoutSeconds: 30, maxAttempts: 2, ...overrides
})

test('FLOW-018 graph editor validates a schema-v1 diamond and computes positions outside graph JSON', () => {
  const graph = {
    schemaVersion: 1,
    nodes: ['A', 'B', 'C', 'D'].map(id => taskNode(id)),
    edges: [
      { id: 'ab', source: 'A', target: 'B', branch: 'ALWAYS' },
      { id: 'ac', source: 'A', target: 'C', branch: 'ALWAYS' },
      { id: 'bd', source: 'B', target: 'D', branch: 'ALWAYS' },
      { id: 'cd', source: 'C', target: 'D', branch: 'ALWAYS' }
    ]
  }
  assert.deepEqual(validateWorkflowGraph(graph), [])
  const positions = computeDefaultLayout(graph)
  assert.equal(positions.A.x, 70)
  assert.equal(positions.B.x, 330)
  assert.equal(positions.C.x, 330)
  assert.equal(positions.D.x, 590)
  const created = defaultWorkflowGraph()
  assert.deepEqual(validateWorkflowGraph(created), [])
  assert.equal(Object.hasOwn(created.nodes[0], 'position'), false)
})

test('FLOW-018 condition AST supports bounded operators and requires TRUE/FALSE branches', () => {
  const graph = {
    schemaVersion: 1,
    nodes: [
      { id: 'C', type: 'CONDITION', label: 'Route', condition: {
        op: 'and',
        conditions: [
          { op: 'exists', path: '$.region' },
          { op: 'in', path: '$.country', values: ['US', 'CA'] }
        ]
      } },
      taskNode('T'),
      taskNode('F')
    ],
    edges: [
      { id: 'ct', source: 'C', target: 'T', branch: 'TRUE' },
      { id: 'cf', source: 'C', target: 'F', branch: 'FALSE' }
    ]
  }
  assert.deepEqual(validateWorkflowGraph(graph), [])
  const missingBranch = { ...graph, edges: graph.edges.slice(0, 1) }
  assert.ok(validateWorkflowGraph(missingBranch).some(message => message.includes('TRUE edge') || message.includes('FALSE edge')))
  const unsafePath = {
    ...graph,
    nodes: graph.nodes.map(node => node.id === 'C'
      ? { ...node, condition: { op: 'exists', path: '$.user; DROP TABLE users' } }
      : node)
  }
  assert.ok(validateWorkflowGraph(unsafePath).some(message => message.includes('safe field path')))
})

test('FLOW-018 graph validator rejects cycles, unsafe handlers, excess depth and unsupported fields', () => {
  const diamond = {
    schemaVersion: 1,
    nodes: ['A', 'B', 'C', 'D'].map(id => taskNode(id)),
    edges: [
      { id: 'ab', source: 'A', target: 'B', branch: 'ALWAYS' },
      { id: 'ac', source: 'A', target: 'C', branch: 'ALWAYS' },
      { id: 'bd', source: 'B', target: 'D', branch: 'ALWAYS' },
      { id: 'cd', source: 'C', target: 'D', branch: 'ALWAYS' }
    ]
  }
  assert.ok(validateWorkflowGraph({ ...diamond, edges: [
    ...diamond.edges, { id: 'da', source: 'D', target: 'A', branch: 'ALWAYS' }
  ] }).some(message => message.includes('cycle')))
  assert.ok(validateWorkflowGraph({ ...diamond, nodes: diamond.nodes.map(node =>
    node.id === 'A' ? { ...node, handlerKey: 'java.lang.Runtime.exec' } : node
  ) }).some(message => message.includes('safe.echo or safe.fail')))
  assert.ok(validateWorkflowGraph({ ...diamond, nodes: diamond.nodes.map(node =>
    node.id === 'A' ? { ...node, unexpected: true } : node
  ) }).some(message => message.includes('unsupported field')))
  const deepNodes = Array.from({ length: 33 }, (_, index) => taskNode('n' + index))
  const deepEdges = Array.from({ length: 32 }, (_, index) => ({
    id: 'e' + index, source: 'n' + index, target: 'n' + (index + 1), branch: 'ALWAYS'
  }))
  assert.ok(validateWorkflowGraph({ schemaVersion: 1, nodes: deepNodes, edges: deepEdges })
    .some(message => message.includes('depth exceeds 32')))
  const oversized = Array.from({ length: 101 }, (_, index) => taskNode('n' + index))
  assert.ok(validateWorkflowGraph({ schemaVersion: 1, nodes: oversized, edges: [] })
    .some(message => message.includes('1–100 nodes')))
})

test('FLOW-018 serializer strips Vue Flow coordinates and runtime metadata before save or publish', () => {
  const graph = {
    schemaVersion: 1,
    nodes: [
      { ...taskNode('B'), position: { x: 900, y: 300 }, data: { internal: true }, selected: true },
      taskNode('A')
    ],
    edges: [{
      id: 'ab', source: 'A', target: 'B', branch: 'ALWAYS',
      markerEnd: 'ArrowClosed', label: 'ALWAYS', data: { branch: 'ALWAYS' }
    }]
  }
  const persisted = JSON.parse(serializeWorkflowGraph(graph))
  assert.deepEqual(persisted.nodes.map(node => node.id), ['A', 'B'])
  assert.equal(Object.hasOwn(persisted.nodes[1], 'position'), false)
  assert.equal(Object.hasOwn(persisted.nodes[1], 'data'), false)
  assert.equal(Object.hasOwn(persisted.nodes[1], 'selected'), false)
  assert.equal(Object.hasOwn(persisted.edges[0], 'markerEnd'), false)
  assert.equal(Object.hasOwn(persisted.edges[0], 'label'), false)
  assert.equal(Object.hasOwn(persisted.edges[0], 'data'), false)
  assert.deepEqual(validateWorkflowGraph(persisted), [])
})
