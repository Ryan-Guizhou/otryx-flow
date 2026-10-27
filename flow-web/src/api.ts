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
  status: 'WAITING_DISPATCH' | 'DISPATCHED' | 'SUCCEEDED' | 'FAILED'
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

/** M3 FLOW-015: definition publishing only; DAG execution and canvas are separate issues. */
export type WorkflowScalar = string | number | boolean | null
export type WorkflowCondition =
  | { op: 'equals' | 'notEquals'; path: string; value: WorkflowScalar }
  | { op: 'exists'; path: string }
  | { op: 'in'; path: string; values: WorkflowScalar[] }
  | { op: 'and' | 'or'; conditions: WorkflowCondition[] }
export interface WorkflowTaskNode {
  id: string
  type: 'TASK'
  label?: string
  handlerKey: 'safe.echo' | 'safe.fail'
  parameters: Record<string, unknown>
  timeoutSeconds: number
  maxAttempts: number
}
export interface WorkflowConditionNode {
  id: string
  type: 'CONDITION'
  label?: string
  condition: WorkflowCondition
}
export type WorkflowNode = WorkflowTaskNode | WorkflowConditionNode
export type WorkflowEdge = {
  id: string
  source: string
  target: string
  branch: 'ALWAYS' | 'TRUE' | 'FALSE'
}
export interface WorkflowGraphV1 {
  schemaVersion: 1
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}
export interface WorkflowDefinition {
  id: string
  projectId: string
  name: string
  graphJson: string
  draftVersion: number
  publishedRevision: number
  lastPublishedDraftVersion: number
  updatedAt: string
}
export interface WorkflowRevision {
  workflowId: string
  revision: number
  name: string
  graphJson: string
  graphSha256: string
  sourceDraftVersion: number
  publishedAt: string
}
export interface WorkflowDraftInput { name: string; graphJson: string }
const workflowsPath = (projectId: string) =>
  '/api/v1/projects/' + encodeURIComponent(projectId) + '/workflows'

export async function listWorkflows(projectId: string): Promise<WorkflowDefinition[]> {
  return call(workflowsPath(projectId))
}
export async function createWorkflowDraft(projectId: string,
  input: WorkflowDraftInput, requestKey: string): Promise<WorkflowDefinition> {
  return call(workflowsPath(projectId), {
    method: 'POST', headers: { 'Idempotency-Key': requestKey },
    body: JSON.stringify(input)
  })
}
export async function saveWorkflowDraft(projectId: string,
  workflow: WorkflowDefinition, input: WorkflowDraftInput): Promise<WorkflowDefinition> {
  return call(workflowsPath(projectId) + '/' + encodeURIComponent(workflow.id) + '/draft', {
    method: 'PUT',
    body: JSON.stringify({ ...input, expectedDraftVersion: workflow.draftVersion })
  })
}
export async function publishWorkflow(projectId: string,
  workflow: WorkflowDefinition, requestKey: string): Promise<WorkflowDefinition> {
  return call(workflowsPath(projectId) + '/' + encodeURIComponent(workflow.id) + '/publish', {
    method: 'POST', headers: { 'Idempotency-Key': requestKey },
    body: JSON.stringify({ expectedDraftVersion: workflow.draftVersion })
  })
}
export async function getWorkflowRevision(projectId: string, workflowId: string,
  revision: number): Promise<WorkflowRevision> {
  return call(workflowsPath(projectId) + '/' + encodeURIComponent(workflowId) +
    '/revisions/' + revision)
}

/** M3 FLOW-016: durable DAG instances distinct from M2 short-task Runs. */
export interface DagRun {
  id: string
  projectId: string
  workflowId: string
  workflowRevision: number
  graphSha256: string
  status: 'RUNNING' | 'CANCEL_REQUESTED' | 'CANCELLED' | 'SUCCEEDED' | 'FAILED'
  createdAt: string
  updatedAt: string
  runVersion: number
}
export type DagNodePhase = 'BLOCKED' | 'READY' | 'DISPATCHED' |
  'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCEL_PENDING' | 'CANCELLED'
export interface DagNode {
  id: string
  nodeId: string
  type: 'TASK' | 'CONDITION'
  status: DagNodePhase
  conditionDecision: boolean | null
  updatedAt: string
  logicalAttempt: number
}
export interface DagRunDetail { run: DagRun; nodes: DagNode[] }
export interface DagStartInput { workflowId: string; workflowRevision: number; inputJson: string }
const dagPath = (projectId: string) =>
  '/api/v1/projects/' + encodeURIComponent(projectId) + '/workflow-runs'

export async function startDagRun(projectId: string,
  input: DagStartInput, requestKey: string): Promise<DagRun> {
  return call(dagPath(projectId), {
    method: 'POST', headers: { 'Idempotency-Key': requestKey },
    body: JSON.stringify(input)
  })
}
export async function listDagRuns(projectId: string): Promise<DagRun[]> {
  return call(dagPath(projectId))
}
export async function getDagRun(projectId: string, runId: string): Promise<DagRunDetail> {
  return call(dagPath(projectId) + '/' + encodeURIComponent(runId))
}

/** FLOW-017: cancel intent and manual logical retry are separately idempotent operations. */
export interface DagControlReply {
  code: 'OK' | 'DUPLICATE'
  run: DagRun
  nodeRunId: string | null
  duplicate: boolean
}
export interface DagControlAudit {
  action: 'CANCEL' | 'RETRY'
  nodeId: string | null
  oldVersion: number
  newVersion: number
  nodeRunId: string | null
  createdAt: string
}
export async function cancelDagRun(projectId: string, runId: string,
  expectedRunVersion: number, requestKey: string): Promise<DagControlReply> {
  return call(dagPath(projectId) + '/' + encodeURIComponent(runId) + '/cancel', {
    method: 'POST', headers: { 'Idempotency-Key': requestKey },
    body: JSON.stringify({ expectedRunVersion })
  })
}
export async function retryDagNode(projectId: string, runId: string, nodeId: string,
  expectedRunVersion: number, requestKey: string): Promise<DagControlReply> {
  return call(dagPath(projectId) + '/' + encodeURIComponent(runId) +
    '/nodes/' + encodeURIComponent(nodeId) + '/retry', {
    method: 'POST', headers: { 'Idempotency-Key': requestKey },
    body: JSON.stringify({ expectedRunVersion })
  })
}
export async function listDagControlHistory(projectId: string,
  runId: string): Promise<DagControlAudit[]> {
  return call(dagPath(projectId) + '/' + encodeURIComponent(runId) + '/history')
}
