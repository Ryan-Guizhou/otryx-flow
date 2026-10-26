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

export interface CronOccurrence { utc: string; local: string; offset: string }
export interface CronPreview {
  expression: string
  timeZone: string
  fromUtc: string
  occurrences: CronOccurrence[]
  overlapOccurrencesSkipped: number
}
export async function previewCron(expression: string, timeZone: string, fromUtc: string, limit = 8): Promise<CronPreview> {
  return call('/api/v1/schedules/preview', {
    method: 'POST', body: JSON.stringify({ expression, timeZone, fromUtc, limit })
  })
}

/** Persisted Scheduler definition bound to one published JobRevision. */
export interface Schedule {
  id: string
  projectId: string
  jobId: string
  jobRevision: number
  expression: string
  timeZone: string
  misfirePolicy: 'SKIP' | 'FIRE_ONCE' | 'CATCH_UP'
  enabled: boolean
  version: number
  nextFireUtc: string
}
export interface ScheduleTrigger {
  id: string
  scheduleId: string
  scheduleRevision: number
  plannedFireUtc: string
  status: 'PENDING_ENGINE'
  createdAt: string
}
export interface ScheduleInput {
  jobId: string
  jobRevision: number
  expression: string
  timeZone: string
  misfirePolicy: 'SKIP' | 'FIRE_ONCE' | 'CATCH_UP'
}
export async function listSchedules(projectId: string): Promise<Schedule[]> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/schedules`)
}
export async function createSchedule(projectId: string, input: ScheduleInput, requestKey: string): Promise<Schedule> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/schedules`, {
    method: 'POST', headers: { 'Idempotency-Key': requestKey }, body: JSON.stringify(input)
  })
}
export async function listScheduleTriggers(projectId: string, scheduleId: string): Promise<ScheduleTrigger[]> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/schedules/${encodeURIComponent(scheduleId)}/triggers`)
}
export async function toggleSchedule(projectId: string, scheduleId: string,
  expectedVersion: number, enabled: boolean): Promise<Schedule> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/schedules/${encodeURIComponent(scheduleId)}/enabled`, {
    method: 'POST', body: JSON.stringify({ expectedVersion, enabled })
  })
}

/** Engine Run is durable; WAITING_DISPATCH is not task execution. */
export interface EngineRun {
  id: string
  eventId: string
  projectId: string
  scheduleId: string
  scheduleRevision: number
  jobId: string
  jobRevision: number
  plannedFireUtc: string
  origin: string
  status: 'WAITING_DISPATCH' | 'DISPATCHED'
  createdAt: string
}
export async function listRuns(projectId: string): Promise<EngineRun[]> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/runs`)
}

/** Read-only durable queue; leaseToken is never included in browser responses. */
export interface DispatchTask {
  id: string
  runId: string
  projectId: string
  handlerKey: string
  status: 'READY' | 'LEASED' | 'SUCCEEDED' | 'FAILED'
  attempts: number
  maxAttempts: number
  leaseEpoch: number
  leaseUntilUtc: string | null
  updatedAt: string
}
export async function listDispatchTasks(projectId: string): Promise<DispatchTask[]> {
  return call(`/api/v1/projects/${encodeURIComponent(projectId)}/dispatch-tasks`)
}
