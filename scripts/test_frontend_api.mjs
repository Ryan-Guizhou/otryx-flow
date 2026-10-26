/** Offline HTTP adapter verification using Node 22; this is NOT a Vue/browser or RPC integration test. */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  refreshCsrf, currentUser, login, logout, listProjects, createProject
} from '../flow-web/src/api.ts'

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
