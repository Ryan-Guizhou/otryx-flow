/**
 * Test only the REST/CSRF E2E shell harness against a deliberately fake local server.
 * No MySQL/Etcd/OTRYX RPC, Vue or Spring code is exercised here.
 */
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import http from 'node:http'
import { once } from 'node:events'

const projects = []
const used = new Map()
const send = (res, status, body, headers = {}) => {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers })
  res.end(body === undefined ? '' : JSON.stringify(body))
}
const server = http.createServer(async (req, res) => {
  const parts = []
  for await (const chunk of req) parts.push(chunk)
  const payload = Buffer.concat(parts).toString()
  const cookies = Object.fromEntries((req.headers.cookie || '').split(';').map(x => x.trim().split('=')))
  const csrf = req.headers['x-xsrf-token'] === 'test-token'
  const authorized = cookies.SESSION === 'test-session'
  if (req.url === '/api/v1/auth/csrf') {
    return send(res, 200, { token: 'test-token' }, { 'Set-Cookie': 'XSRF-TOKEN=test-token; Path=/; HttpOnly' })
  }
  if (req.url === '/api/v1/auth/login' && req.method === 'POST') {
    if (!csrf) return send(res, 403, { code: 'CSRF_DENIED' })
    if (JSON.parse(payload).password !== 'offline-test-pass') return send(res, 401, { code: 'INVALID_CREDENTIALS' })
    return send(res, 200, { username: 'admin', role: 'ADMIN' }, { 'Set-Cookie': 'SESSION=test-session; Path=/; HttpOnly' })
  }
  if (req.url === '/api/v1/auth/logout' && req.method === 'POST') {
    if (!csrf || !authorized) return send(res, 403, { code: 'UNAUTHORIZED' })
    return send(res, 204, undefined, { 'Set-Cookie': 'SESSION=; Max-Age=0; Path=/' })
  }
  if (req.url === '/api/v1/projects') {
    if (!authorized) return send(res, 401, { code: 'UNAUTHORIZED' })
    if (req.method === 'GET') return send(res, 200, projects)
    if (!csrf) return send(res, 403, { code: 'CSRF_DENIED' })
    const key = String(req.headers['idempotency-key'] ?? '')
    if (!/^[A-Za-z0-9_-]{16,80}$/.test(key)) return send(res, 400, { code: 'INVALID_IDEMPOTENCY_KEY' })
    const name = JSON.parse(payload).name
    if (used.has(key)) {
      const prior = used.get(key)
      return prior.name === name ? send(res, 200, prior) : send(res, 409, { code: 'IDEMPOTENCY_CONFLICT' })
    }
    const project = { id: 'id-' + (projects.length + 1), name, createdAt: '2026-10-26T00:00:00Z' }
    projects.push(project)
    used.set(key, project)
    return send(res, 201, project)
  }
  return send(res, 404, { code: 'NOT_FOUND' })
})
server.listen(0, '127.0.0.1')
await once(server, 'listening')
const address = server.address()
const child = spawn('bash', ['scripts/test_m1_e2e.sh'], {
  cwd: new URL('..', import.meta.url).pathname,
  env: { ...process.env, FLOW_BASE_URL: `http://127.0.0.1:${address.port}`,
    FLOW_ADMIN_USERNAME: 'admin', FLOW_ADMIN_PASSWORD: 'offline-test-pass',
    FLOW_E2E_RESTART_METADATA: '0' }
})
let stdout = '', stderr = ''
child.stdout.on('data', data => { stdout += data.toString() })
child.stderr.on('data', data => { stderr += data.toString() })
const timer = setTimeout(() => child.kill('SIGTERM'), 15_000)
try {
  const [exit] = await once(child, 'exit')
  assert.equal(exit, 0, 'E2E shell probe failed against fake API:\n' + stdout + '\n' + stderr)
  assert.match(stdout, /PASS: session auth/)
  console.log('PASS: shell E2E probes 401, login, CSRF, replay, conflict, input error and logout against mock HTTP server')
  console.log('NOTE: Fake API only; does not verify the Java backend or OTRYX RPC')
} finally {
  clearTimeout(timer)
  server.close()
}
