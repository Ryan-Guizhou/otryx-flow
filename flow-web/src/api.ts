export interface SessionUser { username: string; role: string }
export interface Project { id: string; name: string; createdAt: string }
export type ApiError = Error & { status?: number; code?: string }

let csrfToken = ''

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (init.method && init.method !== 'GET') headers.set('X-XSRF-TOKEN', csrfToken)
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { code?: string }
    const error = new Error(body.code ?? `HTTP_${response.status}`) as ApiError
    error.status = response.status
    error.code = body.code
    throw error
  }
  if (response.status === 204) return undefined as T
  return await response.json() as T
}

export async function refreshCsrf(): Promise<void> {
  const value = await call<{ token: string }>('/api/v1/auth/csrf')
  csrfToken = value.token
}
export async function currentUser(): Promise<SessionUser> { return call('/api/v1/auth/me') }
export async function login(username: string, password: string): Promise<SessionUser> {
  const user = await call<SessionUser>('/api/v1/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password })
  })
  await refreshCsrf()
  return user
}
export async function logout(): Promise<void> {
  await call('/api/v1/auth/logout', { method: 'POST' })
  await refreshCsrf()
}
export async function listProjects(): Promise<Project[]> { return call('/api/v1/projects') }
export async function createProject(name: string, requestKey: string): Promise<Project> {
  return call('/api/v1/projects', { method: 'POST', headers: { 'Idempotency-Key': requestKey },
    body: JSON.stringify({ name }) })
}

export interface Job {
  id: string
  projectId: string
  name: string
  handlerKey: string
  parametersJson: string
  timeoutSeconds: number
  maxAttempts: number
  draftVersion: number
  publishedRevision: number
  lastPublishedDraftVersion: number
  updatedAt: string
}
export interface JobDraftInput {
  name: string
  handlerKey: string
  parametersJson: string
  timeoutSeconds: number
  maxAttempts: number
}
export async function listJobs(projectId: string): Promise<Job[]> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/jobs`)
}
export async function createJobDraft(projectId: string, input: JobDraftInput, requestKey: string): Promise<Job> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/jobs`, {
    method: 'POST', headers: { 'Idempotency-Key': requestKey }, body: JSON.stringify(input)
  })
}
export async function saveJobDraft(projectId: string, job: Job, input: JobDraftInput): Promise<Job> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/jobs/${encodeURIComponent(job.id)}/draft`, {
    method: 'PUT', body: JSON.stringify({ ...input, expectedDraftVersion: job.draftVersion })
  })
}
export async function publishJob(projectId: string, job: Job, requestKey: string): Promise<Job> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/jobs/${encodeURIComponent(job.id)}/publish`, {
    method: 'POST', headers: { 'Idempotency-Key': requestKey },
    body: JSON.stringify({ expectedDraftVersion: job.draftVersion })
  })
}
