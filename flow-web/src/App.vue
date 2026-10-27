<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import RunPanel from './RunPanel.vue'
import DispatchQueuePanel from './DispatchQueuePanel.vue'
import SchedulePanel from './SchedulePanel.vue'
import WorkflowEditor from './WorkflowEditor.vue'
import type { Project, Job, SessionUser, ApiError, CronPreview } from './api'
import { createProject, currentUser, listProjects, login, logout, refreshCsrf, listJobs, createJobDraft, publishJob, previewCron } from './api'

type ViewKey = 'overview' | 'projects' | 'jobs' | 'workflows' | 'runs' | 'queue' | 'workers'
const activeView = ref<ViewKey>('overview')
const navigation: { id: ViewKey; label: string; icon: string; ready: boolean }[] = [
  { id: 'overview', label: 'Overview', icon: '▦', ready: true },
  { id: 'projects', label: 'Projects', icon: '▤', ready: true },
  { id: 'jobs', label: 'Jobs', icon: 'J', ready: true },
  { id: 'workflows', label: 'Workflows', icon: '◇', ready: true },
  { id: 'runs', label: 'Executions', icon: '◷', ready: true },
  { id: 'queue', label: 'Dispatch queue', icon: '▣', ready: true },
  { id: 'workers', label: 'Worker cluster', icon: '⬡', ready: false },
]
const user = ref<SessionUser | null>(null)
const username = ref('admin')
const password = ref('')
const error = ref('')
const ready = ref(false)
const busy = ref(false)
const projects = ref<Project[]>([])
const projectName = ref('')
const showCreate = ref(false)
const jobs = ref<Job[]>([])
const activeJobProject = ref('')
const showJobCreate = ref(false)
const jobName = ref('')
const jobHandler = ref('safe.echo')
const jobParameters = ref('{}')
const jobTimeout = ref(30)
const jobMaxAttempts = ref(2)
const jobPendingKey = ref<string | null>(null)
const cronExpression = ref('0 0 9 * * *')
const cronTimezone = ref('Asia/Shanghai')
const cronResult = ref<CronPreview | null>(null)
const cronError = ref('')

const pendingKey = ref<string | null>(null)
const backendHealthy = ref(false)
const activeTitle = computed(() => navigation.find(x => x.id === activeView.value)?.label ?? 'Overview')

function friendly(problem: unknown) {
  const e = problem as ApiError
  if (e.status === 503) return 'The metadata service is still starting. Please retry.'
  if (e.status === 401) return 'Invalid credentials or expired session.'
  if (e.status === 403) return 'Action denied. Please reload and try again.'
  if (e.status === 409) return 'This request key was used with different project data.'
  return e.message || 'An unexpected error occurred.'
}
async function reloadProjects() {
  error.value = ''
  try {
    projects.value = await listProjects()
    backendHealthy.value = true
  } catch (err) {
    backendHealthy.value = false
    error.value = friendly(err)
  }
}
onMounted(async () => {
  try {
    await refreshCsrf()
    user.value = await currentUser().catch(() => null)
    if (user.value) await reloadProjects()
  } catch (err) { error.value = friendly(err) }
  finally { ready.value = true }
})
async function signIn() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    user.value = await login(username.value.trim(), password.value)
    password.value = ''
    await reloadProjects()
  } catch (err) { error.value = friendly(err) }
  finally { busy.value = false }
}
async function signOut() {
  busy.value = true; error.value = ''
  try { await logout(); user.value = null; projects.value = []; backendHealthy.value = false }
  catch (err) { error.value = friendly(err) }
  finally { busy.value = false }
}
async function saveProject() {
  if (busy.value || !projectName.value.trim()) return
  busy.value = true; error.value = ''
  if (!pendingKey.value) pendingKey.value = crypto.randomUUID()
  try {
    await createProject(projectName.value.trim(), pendingKey.value)
    pendingKey.value = null; projectName.value = ''; showCreate.value = false
    await reloadProjects()
    activeView.value = 'projects'
  } catch (err) {
    error.value = friendly(err)
    if ((err as ApiError).status === 409) pendingKey.value = null
  } finally { busy.value = false }
}
async function reloadJobs() {
  if (!activeJobProject.value && projects.value.length) activeJobProject.value = projects.value[0]!.id
  if (!activeJobProject.value) { jobs.value = []; return }
  try { jobs.value = await listJobs(activeJobProject.value) }
  catch (err) { error.value = friendly(err) }
}
async function saveJob() {
  if (busy.value || !activeJobProject.value || !jobName.value.trim()) return
  busy.value = true; error.value = ''
  if (!jobPendingKey.value) jobPendingKey.value = crypto.randomUUID()
  try {
    JSON.parse(jobParameters.value)
    await createJobDraft(activeJobProject.value, {
      name: jobName.value.trim(), handlerKey: jobHandler.value.trim(),
      parametersJson: jobParameters.value, timeoutSeconds: jobTimeout.value,
      maxAttempts: jobMaxAttempts.value
    }, jobPendingKey.value)
    jobPendingKey.value = null; showJobCreate.value = false; jobName.value = ''
    await reloadJobs()
  } catch (err) { error.value = friendly(err) }
  finally { busy.value = false }
}
async function releaseJob(job: Job) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await publishJob(activeJobProject.value, job, crypto.randomUUID()); await reloadJobs() }
  catch (err) { error.value = friendly(err) }
  finally { busy.value = false }
}
async function runCronPreview() {
  if (busy.value) return
  busy.value = true
  cronError.value = ''
  cronResult.value = null
  try { cronResult.value = await previewCron(cronExpression.value, cronTimezone.value, new Date().toISOString(), 8) }
  catch (err) { cronError.value = friendly(err) }
  finally { busy.value = false }
}
function onChangeName() { pendingKey.value = null }
function selectView(id: ViewKey) { activeView.value = id; error.value = ''; if (id === 'jobs') void reloadJobs() }
</script>

<template>
  <div v-if="!ready" class="boot-screen"><div class="spinner" aria-hidden="true" /> Initializing secure session...</div>
  <div v-else-if="!user" class="login-layout">
    <section class="login-illustration">
      <div class="brand brand-light"><span class="brand-mark">◈</span><span>OTRYX <b>FLOW</b></span></div>
      <div class="login-story">
        <span class="kicker">DISTRIBUTED ORCHESTRATION</span>
        <h1>Bring order to<br /><em>complex workflows.</em></h1>
        <p>One workspace to coordinate jobs, observe execution, and manage distributed systems with confidence.</p>
        <div class="story-divider" />
        <div class="flow-demo" aria-hidden="true"><div class="flow-node"><span class="node-point violet" />Scheduled</div><div class="flow-link">━━━</div><div class="flow-node"><span class="node-point" />Process</div><div class="flow-link">━━━</div><div class="flow-node"><span class="node-point amber" />Complete</div></div>
      </div>
      <span class="login-footer">Powered by OTRYX RPC · M1 Foundation</span>
    </section>
    <main class="login-form-pane">
      <form class="login-card" @submit.prevent="signIn">
        <div class="login-emblem">◈</div>
        <div class="eyebrow">WELCOME BACK</div>
        <h2>Sign in to OTRYX Flow</h2>
        <p class="muted">Use the admin account configured on your server.</p>
        <label for="user">Username</label><input id="user" v-model="username" autocomplete="username" required minlength="1" maxlength="64" placeholder="Your username" />
        <label for="pass">Password</label><input id="pass" v-model="password" type="password" autocomplete="current-password" required placeholder="Your password" />
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <button type="submit" class="btn btn-primary login-submit" :disabled="busy">{{ busy ? 'Signing in...' : 'Sign in' }} <span>→</span></button>
        <p class="login-tip">Local development only. Do not expose this M1 configuration to the public internet.</p>
      </form>
    </main>
  </div>
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">◈</span><span>OTRYX <b>FLOW</b></span></div>
      <div class="workspace-label">WORKSPACE</div>
      <div class="workspace-card"><span class="workspace-icon">O</span><div><strong>Flow workspace</strong><small>Development environment</small></div><span class="workspace-chevron">⌄</span></div>
      <div class="nav-group-label">PLATFORM</div>
      <nav aria-label="Main navigation"><button v-for="item in navigation" :key="item.id" class="nav-item" :class="{ selected: activeView === item.id }" @click="selectView(item.id)"><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}<span v-if="!item.ready" class="tiny-dot" title="Planned for a future milestone" /></button></nav>
      <div class="sidebar-grow" />
      <div class="sidebar-separator" />
      <div class="sidebar-bottom"><div class="avatar">{{ user.username[0]?.toUpperCase() }}</div><div><strong>{{ user.username }}</strong><small>{{ user.role === 'ADMIN' ? 'Administrator' : 'Read-only' }}</small></div><button class="signout-icon" title="Sign out" @click="signOut" :disabled="busy">↪</button></div>
    </aside>
    <main class="content">
      <header class="topbar"><div class="breadcrumbs">Workspace <span>/</span> <strong>{{ activeTitle }}</strong></div><div class="top-actions"><span class="status-pill"><span :class="['status-dot', backendHealthy ? 'online' : 'offline']" />{{ backendHealthy ? 'RPC connected' : 'RPC unverified' }}</span><button class="icon-btn" title="Reload projects" @click="reloadProjects">↻</button><div class="avatar small">{{ user.username[0]?.toUpperCase() }}</div></div></header>
      <div class="page-body">
        <p v-if="error" class="notice error" role="alert">{{ error }} <button class="inline-retry" @click="reloadProjects">Retry</button></p>
        <template v-if="activeView === 'overview'">
          <div class="page-title-row"><div><div class="eyebrow">CONTROL CENTER</div><h1>Good to see you, {{ user.username }}.</h1><p class="muted">Your distributed orchestration workspace at a glance.</p></div><button class="btn btn-primary" @click="showCreate = true; activeView = 'projects'">+ New project</button></div>
          <div class="stats-grid">
            <div class="stat-card"><div class="stat-label">Projects <span class="stat-icon">▤</span></div><div class="stat-value">{{ projects.length }}</div><div class="stat-detail"><span class="pill-green">Live</span> From metadata RPC</div></div>
            <div class="stat-card"><div class="stat-label">Workflows <span class="stat-icon">◇</span></div><div class="stat-value muted-stat">—</div><div class="stat-detail"><span class="pill-grey">M3</span> Coming next</div></div>
            <div class="stat-card"><div class="stat-label">Executions <span class="stat-icon">◷</span></div><div class="stat-value muted-stat">—</div><div class="stat-detail"><span class="pill-grey">M2</span> Coming next</div></div>
            <div class="stat-card"><div class="stat-label">Workers <span class="stat-icon">⬡</span></div><div class="stat-value muted-stat">—</div><div class="stat-detail"><span class="pill-grey">M2</span> Coming next</div></div>
          </div>
          <div class="overview-grid"><div class="panel"><div class="panel-heading"><div><h3>System architecture</h3><p>Real OTRYX RPC connection to metadata</p></div><span class="label-outline">M1 FOUNDATION</span></div><div class="topology"><div class="topology-node"><span class="topology-icon violet-bg">▣</span><strong>Browser</strong><small>Vue 3</small></div><div class="topology-line">→</div><div class="topology-node"><span class="topology-icon blue-bg">▦</span><strong>Flow API</strong><small>REST / Session</small></div><div class="topology-line active-line">⇢</div><div class="topology-node"><span class="topology-icon green-bg">⬡</span><strong>Metadata</strong><small>OTRYX RPC</small></div></div><div class="topology-caption"><span :class="['status-dot', backendHealthy ? 'online' : 'offline']" /> {{ backendHealthy ? 'Metadata query succeeded across RPC' : 'Waiting for metadata query' }}</div></div><div class="panel milestone-panel"><div class="panel-heading"><div><h3>Build progress</h3><p>Milestone roadmap</p></div></div><div class="milestone-entry complete"><span class="step-circle">✓</span><div><strong>M0 · Product blueprint</strong><small>Requirements and architecture</small></div></div><div class="milestone-entry current"><span class="step-circle">1</span><div><strong>M1 · Service foundation</strong><small>Authentication, RPC and projects</small></div></div><div class="milestone-entry"><span class="step-circle">2</span><div><strong>M2 · Task scheduling</strong><small>Durable jobs and worker leases</small></div></div><div class="milestone-entry"><span class="step-circle">3</span><div><strong>M3 · Workflow canvas</strong><small>DAG editor and state engine</small></div></div></div></div>
          <div class="panel projects-preview"><div class="panel-heading"><div><h3>Recent projects</h3><p>Your latest workspaces</p></div><button class="btn btn-text" @click="activeView = 'projects'">View all →</button></div><div v-if="!projects.length" class="empty-mini">No projects yet. Create the first one to verify the end-to-end RPC flow.</div><div v-else class="project-list"><div v-for="project in projects.slice(0, 4)" :key="project.id" class="project-row"><span class="project-icon">▤</span><div><strong>{{ project.name }}</strong><small>{{ project.id }}</small></div><span class="project-date">{{ new Date(project.createdAt).toLocaleDateString() }}</span></div></div></div>
        </template>
        <template v-else-if="activeView === 'projects'">
          <div class="page-title-row"><div><div class="eyebrow">ORGANIZATION</div><h1>Projects</h1><p class="muted">Projects live in the independent metadata service, accessed via RPC.</p></div><button class="btn btn-primary" @click="showCreate = true">+ Create project</button></div>
          <div class="panel table-panel"><div class="panel-heading"><div><h3>All projects <span class="count-tag">{{ projects.length }}</span></h3><p>Visible to your signed-in account</p></div><button class="btn btn-outline" @click="reloadProjects">↻ Refresh</button></div><table v-if="projects.length" class="projects-table"><thead><tr><th>Project</th><th>Project ID</th><th>Created</th><th>State</th></tr></thead><tbody><tr v-for="project in projects" :key="project.id"><td><span class="project-icon">▤</span><strong>{{ project.name }}</strong></td><td class="mono muted">{{ project.id }}</td><td>{{ new Date(project.createdAt).toLocaleDateString() }}</td><td><span class="pill-green">Active</span></td></tr></tbody></table><div v-else class="empty-state"><div class="empty-icon">▤</div><h3>No projects yet</h3><p>Start a project to organize scheduled tasks and workflows.</p><button class="btn btn-primary" @click="showCreate = true">Create first project</button></div></div>
        </template>
        <template v-else-if="activeView === 'jobs'">
          <div class="page-title-row"><div><div class="eyebrow">SCHEDULING</div><h1>Job definitions</h1><p class="muted">Save drafts and publish immutable handler revisions.</p></div><button class="btn btn-primary" @click="showJobCreate = true" :disabled="!projects.length">+ New job</button></div>
          <div class="panel table-panel">
            <div class="panel-heading"><div><h3>Jobs <span class="count-tag">{{ jobs.length }}</span></h3><p>All definitions belong to an owned project</p></div>
              <select v-model="activeJobProject" aria-label="Select project" @change="reloadJobs"><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option></select>
              <button class="btn btn-outline" @click="reloadJobs">Refresh</button>
            </div>
            <table v-if="jobs.length" class="projects-table"><thead><tr><th>Job</th><th>Handler key</th><th>Draft</th><th>Published</th><th>Action</th></tr></thead>
              <tbody><tr v-for="job in jobs" :key="job.id"><td><strong>{{ job.name }}</strong><small class="muted"> {{ job.id }}</small></td><td class="mono">{{ job.handlerKey }}</td><td>v{{ job.draftVersion }}</td><td>r{{ job.publishedRevision || 'none' }}</td><td><button class="btn btn-outline" @click="releaseJob(job)" :disabled="busy || job.draftVersion <= job.lastPublishedDraftVersion">Publish revision</button></td></tr></tbody></table>
            <div v-else class="empty-state"><div class="empty-icon">J</div><h3>No jobs in this project</h3><p>Create a safe Handler draft; execution becomes available in the Dispatcher milestone.</p></div>
          </div>
          <SchedulePanel :project-id="activeJobProject" :jobs="jobs" />
          <div class="panel cron-panel">
            <h3>Cron &amp; time-zone preview</h3>
            <p class="muted">Preview only: these times are not yet saved or scheduled. Six fields (seconds minutes hours day month weekday). DST gaps are skipped and ambiguous clock times run at the earlier offset.</p>
            <div class="cron-form">
              <label for="cron-expression">Cron expression<input id="cron-expression" v-model="cronExpression" placeholder="0 0 9 * * *" autocomplete="off" /></label>
              <label for="cron-zone">IANA time zone<input id="cron-zone" v-model="cronTimezone" placeholder="Asia/Shanghai" autocomplete="off" /></label>
              <button class="btn btn-primary" :disabled="busy" @click="runCronPreview">Preview next 8</button>
            </div>
            <p v-if="cronError" class="notice error" role="alert">{{ cronError }}</p>
            <table v-if="cronResult?.occurrences.length" class="projects-table"><thead><tr><th>UTC instant</th><th>Local clock</th><th>UTC offset</th></tr></thead>
              <tbody><tr v-for="fire in cronResult.occurrences" :key="fire.utc"><td class="mono">{{ fire.utc }}</td><td class="mono">{{ fire.local }}</td><td>{{ fire.offset }}</td></tr></tbody></table>
            <p v-if="cronResult && !cronResult.occurrences.length" class="muted">No future occurrence was found.</p>
          </div>
        </template>
        <template v-else-if="activeView === 'workflows'"><WorkflowEditor :projects="projects" :user-role="user.role" /></template>
        <template v-else-if="activeView === 'runs'"><RunPanel :projects="projects" /></template>
        <template v-else-if="activeView === 'queue'"><DispatchQueuePanel :projects="projects" /></template>
        <template v-else><div class="page-title-row"><div><div class="eyebrow">UPCOMING CAPABILITY</div><h1>{{ activeTitle }}</h1><p class="muted">This module is part of the approved project roadmap.</p></div><span class="label-outline">NOT YET IMPLEMENTED</span></div><div class="panel coming-panel"><div class="coming-glyph">◇</div><h2>Coming in {{ activeView === 'workflows' ? 'M3' : 'M2' }}</h2><p>We don't display simulated task runs or invented cluster health data. This page will be activated after the real service and tests are shipped.</p><button class="btn btn-outline" @click="activeView = 'overview'">Back to overview</button></div></template>
      </div>
    </main>
    <div v-if="showJobCreate" class="modal-backdrop" @click.self="showJobCreate = false"><form class="modal" @submit.prevent="saveJob">
      <div class="modal-top"><div class="eyebrow">NEW JOB DRAFT</div><button type="button" class="icon-btn" aria-label="Close" @click="showJobCreate = false">×</button></div>
      <h2>Create a job</h2><p class="muted">Definitions are stored via the OTRYX RPC metadata provider. This does not execute the Handler yet.</p>
      <label for="job-name">Name</label><input id="job-name" v-model="jobName" required maxlength="100" @input="jobPendingKey = null" />
      <label for="job-handler">Handler key</label><input id="job-handler" v-model="jobHandler" required pattern="[a-z][a-z0-9_.-]{2,127}" @input="jobPendingKey = null" />
      <label for="job-input">JSON object parameters</label><textarea id="job-input" v-model="jobParameters" rows="3" maxlength="16384" @input="jobPendingKey = null" />
      <label for="job-timeout">Timeout (seconds)</label><input id="job-timeout" v-model.number="jobTimeout" type="number" min="1" max="86400" />
      <label for="job-attempts">Maximum attempts</label><input id="job-attempts" v-model.number="jobMaxAttempts" type="number" min="1" max="10" />
      <div class="modal-actions"><button class="btn btn-outline" type="button" @click="showJobCreate = false">Cancel</button><button class="btn btn-primary" type="submit" :disabled="busy || !activeJobProject">Save draft</button></div>
    </form></div>
    <div v-if="showCreate" class="modal-backdrop" @click.self="showCreate = false"><form class="modal" @submit.prevent="saveProject"><div class="modal-top"><div class="eyebrow">NEW WORKSPACE</div><button type="button" class="icon-btn" aria-label="Close" @click="showCreate = false">×</button></div><h2>Create a project</h2><p class="muted">Stored in flow-metadata through a real RPC command.</p><label for="project-name">Project name</label><input id="project-name" v-model="projectName" maxlength="100" required placeholder="e.g. Daily data pipelines" autofocus @input="onChangeName" /><div class="modal-actions"><button type="button" class="btn btn-outline" @click="showCreate = false">Cancel</button><button type="submit" class="btn btn-primary" :disabled="busy || !projectName.trim()">{{ busy ? 'Creating...' : 'Create project' }}</button></div></form></div>
  </div>
</template>
