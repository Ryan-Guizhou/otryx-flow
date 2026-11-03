<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import RunPanel from './RunPanel.vue'
import DispatchQueuePanel from './DispatchQueuePanel.vue'
import SchedulePanel from './SchedulePanel.vue'
import WorkflowEditor from './WorkflowEditor.vue'
import DagRunPanel from './DagRunPanel.vue'
import SettingsPanel from './SettingsPanel.vue'
import WorkerPanel from './WorkerPanel.vue'
import AuditPanel from './AuditPanel.vue'
import ExecutionCenter from './ExecutionCenter.vue'
import LocaleSwitch from './LocaleSwitch.vue'
import type { Project, Job, SessionUser, ApiError, CronPreview, OverviewCounts } from './api'
import { archiveJob, changePassword, createProject, currentUser, listProjects, login, logout, overviewCounts, refreshCsrf, listJobs, createJobDraft, publishJob, previewCron, saveJobDraft, setUnauthorizedHandler, startJobRun } from './api'
import { formatWhen } from './i18n'

type ViewKey = 'overview' | 'projects' | 'jobs' | 'workflows' | 'runs' | 'dag-runs' | 'queue' | 'workers' | 'settings' | 'audit'
const { t, te, locale } = useI18n()
const activeView = ref<ViewKey>('overview')
const navigation = computed(() => [
  { id: 'overview' as const, label: t('nav.overview'), icon: '▦', ready: true },
  { id: 'projects' as const, label: t('nav.projects'), icon: '▤', ready: true },
  { id: 'jobs' as const, label: t('nav.jobs'), icon: 'J', ready: true },
  { id: 'workflows' as const, label: t('nav.workflows'), icon: '◇', ready: true },
  { id: 'runs' as const, label: t('nav.executions'), icon: '◷', ready: true },
  { id: 'dag-runs' as const, label: t('nav.workflowRuns'), icon: '◇', ready: true },
  { id: 'queue' as const, label: t('nav.queue'), icon: '▣', ready: true },
  { id: 'workers' as const, label: t('nav.workers'), icon: '⬡', ready: true },
  { id: 'settings' as const, label: t('nav.limits'), icon: '⚙', ready: true },
  { id: 'audit' as const, label: t('nav.audit'), icon: '☰', ready: true },
])
const user = ref<SessionUser | null>(null)
const username = ref('admin')
const password = ref('')
const error = ref('')
const errorParams = ref<Record<string, string | number>>({})
const ready = ref(false)
const busy = ref(false)
const projects = ref<Project[]>([])
const projectName = ref('')
const showCreate = ref(false)
const jobs = ref<Job[]>([])
const activeJobProject = ref('')
const showJobCreate = ref(false)
const editingJob = ref<Job | null>(null)
const confirmLogout = ref(false)
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
const counts = ref<OverviewCounts>({ projects: 0, jobs: 0, runs: 0, workersOnline: 0 })
const showPassword = ref(false)
const currentPassword = ref('')
const nextPassword = ref('')
const canOperate = computed(() => user.value?.role === 'ADMIN' || user.value?.role === 'OPERATOR')

const pendingKey = ref<string | null>(null)
const backendHealthy = ref(false)
const activeTitle = computed(() => navigation.value.find(x => x.id === activeView.value)?.label ?? t('nav.overview'))

function roleName(role: string) {
  const key = 'role.' + role
  return te(key) ? t(key) : role
}
function showError(key: string, params: Record<string, string | number> = {}) {
  error.value = key
  errorParams.value = params
}
function clearError() {
  error.value = ''
  errorParams.value = {}
}
function friendly(problem: unknown) {
  const e = problem as ApiError
  if (e.status === 503) return 'errors.metadataStarting'
  if (e.code === 'SCHEDULE_STILL_ACTIVE') return 'errors.scheduleStillActive'
  if (e.code === 'HANDLER_UNAVAILABLE') return 'errors.handlerUnavailable'
  if (e.status === 401) return 'errors.invalidCredentials'
  if (e.status === 403) return 'errors.denied'
  if (e.status === 409) return 'errors.conflict'
  return 'errors.unexpected'
}
async function reloadProjects() {
  clearError()
  try {
    projects.value = await listProjects()
    backendHealthy.value = true
    counts.value = await overviewCounts().catch(() => counts.value)
  } catch (err) {
    backendHealthy.value = false
    showError(friendly(err))
  }
}
onMounted(async () => {
  setUnauthorizedHandler(() => {
    if (!user.value) return
    user.value = null
    projects.value = []
    showError('errors.sessionExpired')
  })
  try {
    await refreshCsrf()
    user.value = await currentUser().catch(() => null)
    if (user.value) await reloadProjects()
  } catch (err) { showError(friendly(err)) }
  finally { ready.value = true }
})
async function signIn() {
  if (busy.value) return
  busy.value = true; clearError()
  try {
    user.value = await login(username.value.trim(), password.value)
    password.value = ''
    await reloadProjects()
  } catch (err) { showError(friendly(err)) }
  finally { busy.value = false }
}
async function signOut() {
  busy.value = true; clearError(); confirmLogout.value = false
  try { await logout(); user.value = null; projects.value = []; backendHealthy.value = false }
  catch (err) { showError(friendly(err)) }
  finally { busy.value = false }
}
async function saveProject() {
  if (busy.value || !projectName.value.trim()) return
  busy.value = true; clearError()
  if (!pendingKey.value) pendingKey.value = crypto.randomUUID()
  try {
    await createProject(projectName.value.trim(), pendingKey.value)
    pendingKey.value = null; projectName.value = ''; showCreate.value = false
    await reloadProjects()
    activeView.value = 'projects'
  } catch (err) {
    showError(friendly(err))
    if ((err as ApiError).status === 409) pendingKey.value = null
  } finally { busy.value = false }
}
async function reloadJobs() {
  if (!activeJobProject.value && projects.value.length) activeJobProject.value = projects.value[0]!.id
  if (!activeJobProject.value) { jobs.value = []; return }
  try { jobs.value = await listJobs(activeJobProject.value) }
  catch (err) { showError(friendly(err)) }
}
function openJob(job: Job | null) {
  editingJob.value = job
  jobName.value = job?.name ?? ''
  jobHandler.value = job?.handlerKey ?? 'safe.echo'
  jobParameters.value = job?.parametersJson ?? '{}'
  jobTimeout.value = job?.timeoutSeconds ?? 30
  jobMaxAttempts.value = job?.maxAttempts ?? 2
  jobPendingKey.value = null
  showJobCreate.value = true
}
async function saveJob() {
  if (busy.value || !activeJobProject.value || !jobName.value.trim()) return
  busy.value = true; clearError()
  const input = {
    name: jobName.value.trim(), handlerKey: jobHandler.value.trim(),
    parametersJson: jobParameters.value, timeoutSeconds: jobTimeout.value,
    maxAttempts: jobMaxAttempts.value
  }
  try {
    JSON.parse(jobParameters.value)
    if (editingJob.value) await saveJobDraft(activeJobProject.value, editingJob.value, input)
    else {
      if (!jobPendingKey.value) jobPendingKey.value = crypto.randomUUID()
      await createJobDraft(activeJobProject.value, input, jobPendingKey.value)
    }
    jobPendingKey.value = null; showJobCreate.value = false; editingJob.value = null
    await reloadJobs()
  } catch (err) { showError(friendly(err)) }
  finally { busy.value = false }
}
async function releaseJob(job: Job) {
  if (busy.value) return
  busy.value = true; clearError()
  try { await publishJob(activeJobProject.value, job, crypto.randomUUID()); await reloadJobs() }
  catch (err) { showError(friendly(err)) }
  finally { busy.value = false }
}
async function runJob(job: Job) {
  if (!canOperate.value || !job.publishedRevision || busy.value) return
  busy.value = true; clearError()
  try {
    const created = await startJobRun(activeJobProject.value, job.id, job.publishedRevision, crypto.randomUUID())
    activeView.value = 'runs'
    if (created.status === 'WAITING_DISPATCH') showError('errors.runAccepted', { id: created.runId })
  } catch (err) { showError(friendly(err)) }
  finally { busy.value = false }
}
async function archive(job: Job) {
  if (!canOperate.value || busy.value) return
  busy.value = true
  try { await archiveJob(activeJobProject.value, job); await reloadJobs() }
  catch (err) { showError(friendly(err)) }
  finally { busy.value = false }
}
async function savePassword() {
  busy.value = true; clearError()
  try {
    await changePassword(currentPassword.value, nextPassword.value)
    user.value = null
    showPassword.value = false
  } catch (err) { showError(friendly(err)) }
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
function selectView(id: ViewKey) { activeView.value = id; clearError(); if (id === 'jobs') void reloadJobs() }
</script>

<template>
  <div v-if="!ready" class="boot-screen">
    <LocaleSwitch class="boot-locale" />
    <div class="spinner" aria-hidden="true" /> {{ t('shell.boot') }}
  </div>
  <div v-else-if="!user" class="login-layout">
    <section class="login-illustration">
      <div class="brand brand-light"><span class="brand-mark">◈</span><span>OTRYX <b>FLOW</b></span></div>
      <div class="login-story">
        <span class="kicker">{{ t('login.kicker') }}</span>
        <h1>{{ t('login.titleLead') }}<br /><em>{{ t('login.titleEmphasis') }}</em></h1>
        <p>{{ t('login.story') }}</p>
        <div class="story-divider" />
        <div class="flow-demo" aria-hidden="true"><div class="flow-node"><span class="node-point violet" />{{ t('login.scheduled') }}</div><div class="flow-link">━━━</div><div class="flow-node"><span class="node-point" />{{ t('login.process') }}</div><div class="flow-link">━━━</div><div class="flow-node"><span class="node-point amber" />{{ t('login.complete') }}</div></div>
      </div>
      <span class="login-footer">{{ t('login.footer') }}</span>
    </section>
    <main class="login-form-pane">
      <LocaleSwitch class="login-locale" />
      <form class="login-card" @submit.prevent="signIn">
        <div class="login-emblem">◈</div>
        <div class="eyebrow">{{ t('login.welcome') }}</div>
        <h2>{{ t('login.heading') }}</h2>
        <p class="muted">{{ t('login.hint') }}</p>
        <label for="user">{{ t('login.username') }}</label><input id="user" v-model="username" autocomplete="username" required minlength="1" maxlength="64" :placeholder="t('login.usernamePlaceholder')" />
        <label for="pass">{{ t('login.password') }}</label><input id="pass" v-model="password" type="password" autocomplete="current-password" required :placeholder="t('login.passwordPlaceholder')" />
        <p v-if="error" class="notice error" role="alert">{{ t(error, errorParams) }}</p>
        <button type="submit" class="btn btn-primary login-submit" :disabled="busy">{{ busy ? t('login.submitting') : t('login.submit') }} <span>→</span></button>
        <p class="login-tip">{{ t('login.tip') }}</p>
      </form>
    </main>
  </div>
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">◈</span><span>OTRYX <b>FLOW</b></span></div>
      <div class="workspace-label">{{ t('shell.workspace') }}</div>
      <div class="workspace-card"><span class="workspace-icon">O</span><div><strong>{{ t('shell.workspaceName') }}</strong><small>{{ t('shell.environment') }}</small></div><span class="workspace-chevron">⌄</span></div>
      <div class="nav-group-label">{{ t('shell.platform') }}</div>
      <nav :aria-label="t('nav.main')"><button v-for="item in navigation" :key="item.id" class="nav-item" :class="{ selected: activeView === item.id }" @click="selectView(item.id)"><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}<span v-if="!item.ready" class="tiny-dot" :title="t('nav.planned')" /></button></nav>
      <div class="sidebar-grow" />
      <div class="sidebar-separator" />
      <div class="sidebar-bottom"><div class="avatar">{{ user.username[0]?.toUpperCase() }}</div><div><strong>{{ user.username }}</strong><small>{{ roleName(user.role) }}</small></div><button class="signout-icon" :title="t('shell.changePassword')" @click="showPassword = true">*</button><button class="signout-icon" :title="t('shell.signOut')" @click="confirmLogout = true" :disabled="busy">↪</button></div>
    </aside>
    <main class="content">
      <header class="topbar"><div class="breadcrumbs">{{ t('shell.breadcrumbs') }} <span>/</span> <strong>{{ activeTitle }}</strong></div><div class="top-actions"><LocaleSwitch /><span class="status-pill"><span :class="['status-dot', backendHealthy ? 'online' : 'offline']" />{{ backendHealthy ? t('shell.rpcConnected') : t('shell.rpcUnverified') }}</span><button class="icon-btn" :title="t('shell.reloadProjects')" @click="reloadProjects">↻</button><div class="avatar small">{{ user.username[0]?.toUpperCase() }}</div></div></header>
      <div class="page-body">
        <p v-if="error" class="notice error" role="alert">{{ t(error, errorParams) }} <button class="inline-retry" @click="reloadProjects">{{ t('common.retry') }}</button></p>
        <template v-if="activeView === 'overview'">
          <div class="page-title-row"><div><div class="eyebrow">{{ t('overview.eyebrow') }}</div><h1>{{ t('overview.greeting', { name: user.username }) }}</h1><p class="muted">{{ t('overview.subtitle') }}</p></div><button class="btn btn-primary" @click="showCreate = true; activeView = 'projects'">{{ t('overview.newProject') }}</button></div>
          <div class="stats-grid">
            <div class="stat-card"><div class="stat-label">{{ t('overview.projects') }} <span class="stat-icon">▤</span></div><div class="stat-value">{{ counts.projects }}</div><div class="stat-detail"><span class="pill-green">{{ t('common.live') }}</span> {{ t('overview.fromMetadata') }}</div></div>
            <div class="stat-card"><div class="stat-label">{{ t('overview.jobs') }} <span class="stat-icon">J</span></div><div class="stat-value">{{ counts.jobs }}</div><div class="stat-detail"><span class="pill-green">{{ t('common.live') }}</span> {{ t('overview.visibleDefinitions') }}</div></div>
            <div class="stat-card"><div class="stat-label">{{ t('overview.executions') }} <span class="stat-icon">◷</span></div><div class="stat-value">{{ counts.runs }}</div><div class="stat-detail"><span class="pill-green">{{ t('common.live') }}</span> {{ t('overview.persistedRuns') }}</div></div>
            <div class="stat-card"><div class="stat-label">{{ t('overview.workers') }} <span class="stat-icon">⬡</span></div><div class="stat-value">{{ counts.workersOnline }}</div><div class="stat-detail"><span class="pill-green">{{ t('common.live') }}</span> {{ t('overview.heartbeatOnline') }}</div></div>
          </div>
          <div class="overview-grid"><div class="panel"><div class="panel-heading"><div><h3>{{ t('overview.services') }}</h3><p>{{ t('overview.servicesHint') }}</p></div></div><div class="topology"><div class="topology-node"><span class="topology-icon violet-bg">▣</span><strong>{{ t('overview.browser') }}</strong><small>{{ t('overview.vue') }}</small></div><div class="topology-line">→</div><div class="topology-node"><span class="topology-icon blue-bg">▦</span><strong>{{ t('overview.flowApi') }}</strong><small>{{ t('overview.restSession') }}</small></div><div class="topology-line active-line">⇢</div><div class="topology-node"><span class="topology-icon green-bg">⬡</span><strong>{{ t('overview.metadata') }}</strong><small>{{ t('overview.rpc') }}</small></div></div><div class="topology-caption"><span :class="['status-dot', backendHealthy ? 'online' : 'offline']" /> {{ backendHealthy ? t('overview.metadataOk') : t('overview.metadataWait') }}</div></div><div class="panel"><div class="panel-heading"><div><h3>{{ t('overview.aboutTitle') }}</h3><p>{{ t('overview.aboutHint') }}</p></div></div><p>{{ t('overview.aboutBody') }}</p></div></div>
          <div class="panel projects-preview"><div class="panel-heading"><div><h3>{{ t('overview.recent') }}</h3><p>{{ t('overview.recentHint') }}</p></div><button class="btn btn-text" @click="activeView = 'projects'">{{ t('overview.viewAll') }}</button></div><div v-if="!projects.length" class="empty-mini">{{ t('overview.empty') }}</div><div v-else class="project-list"><div v-for="project in projects.slice(0, 4)" :key="project.id" class="project-row"><span class="project-icon">▤</span><div><strong>{{ project.name }}</strong><small>{{ project.id }}</small></div><span class="project-date">{{ formatWhen(project.createdAt, locale, false) }}</span></div></div></div>
        </template>
        <template v-else-if="activeView === 'projects'">
          <div class="page-title-row"><div><div class="eyebrow">{{ t('projects.eyebrow') }}</div><h1>{{ t('projects.title') }}</h1><p class="muted">{{ t('projects.subtitle') }}</p></div><button class="btn btn-primary" @click="showCreate = true">{{ t('projects.create') }}</button></div>
          <div class="panel table-panel"><div class="panel-heading"><div><h3>{{ t('projects.all') }} <span class="count-tag">{{ projects.length }}</span></h3><p>{{ t('projects.visible') }}</p></div><button class="btn btn-outline" @click="reloadProjects">↻ {{ t('common.refresh') }}</button></div><table v-if="projects.length" class="projects-table"><thead><tr><th>{{ t('projects.colProject') }}</th><th>{{ t('projects.colId') }}</th><th>{{ t('projects.colCreated') }}</th><th>{{ t('projects.colState') }}</th></tr></thead><tbody><tr v-for="project in projects" :key="project.id"><td><span class="project-icon">▤</span><strong>{{ project.name }}</strong></td><td class="mono muted">{{ project.id }}</td><td>{{ formatWhen(project.createdAt, locale, false) }}</td><td><span class="pill-green">{{ t('common.active') }}</span></td></tr></tbody></table><div v-else class="empty-state"><div class="empty-icon">▤</div><h3>{{ t('projects.emptyTitle') }}</h3><p>{{ t('projects.emptyBody') }}</p><button class="btn btn-primary" @click="showCreate = true">{{ t('projects.first') }}</button></div></div>
        </template>
        <template v-else-if="activeView === 'jobs'">
          <div class="page-title-row"><div><div class="eyebrow">{{ t('jobs.eyebrow') }}</div><h1>{{ t('jobs.title') }}</h1><p class="muted">{{ t('jobs.subtitle') }}</p></div><button class="btn btn-primary" @click="openJob(null)" :disabled="!projects.length || !canOperate">{{ t('jobs.new') }}</button></div>
          <div class="panel table-panel">
            <div class="panel-heading"><div><h3>{{ t('jobs.heading') }} <span class="count-tag">{{ jobs.length }}</span></h3><p>{{ t('jobs.owned') }}</p></div>
              <select v-model="activeJobProject" :aria-label="t('jobs.selectProject')" @change="reloadJobs"><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option></select>
              <button class="btn btn-outline" @click="reloadJobs">{{ t('common.refresh') }}</button>
            </div>
            <table v-if="jobs.length" class="projects-table"><thead><tr><th>{{ t('jobs.colJob') }}</th><th>{{ t('jobs.colHandler') }}</th><th>{{ t('jobs.colDraft') }}</th><th>{{ t('jobs.colPublished') }}</th><th>{{ t('jobs.colAction') }}</th></tr></thead>
              <tbody><tr v-for="job in jobs" :key="job.id"><td><strong>{{ job.name }}</strong><small class="muted"> {{ job.id }}</small></td><td class="mono">{{ job.handlerKey }}</td><td>v{{ job.draftVersion }}</td><td>r{{ job.publishedRevision || t('common.none') }}</td><td><button class="btn btn-outline" @click="openJob(job)" :disabled="busy || !canOperate">{{ t('jobs.edit') }}</button> <button class="btn btn-outline" @click="releaseJob(job)" :disabled="busy || !canOperate || job.draftVersion <= job.lastPublishedDraftVersion">{{ t('jobs.publish') }}</button> <button class="btn btn-outline" :disabled="busy || !canOperate || !job.publishedRevision || job.enabled === false" @click="runJob(job)">{{ t('jobs.run') }}</button> <button class="btn btn-outline" :disabled="busy || !canOperate" @click="archive(job)">{{ t('common.archive') }}</button></td></tr></tbody></table>
            <div v-else class="empty-state"><div class="empty-icon">J</div><h3>{{ t('jobs.emptyTitle') }}</h3><p>{{ t('jobs.emptyBody') }}</p></div>
          </div>
          <SchedulePanel :project-id="activeJobProject" :jobs="jobs" />
          <div class="panel cron-panel">
            <h3>{{ t('cron.title') }}</h3>
            <p class="muted">{{ t('cron.hint') }}</p>
            <div class="cron-form">
              <label for="cron-expression">{{ t('cron.expression') }}<input id="cron-expression" v-model="cronExpression" placeholder="0 0 9 * * *" autocomplete="off" /></label>
              <label for="cron-zone">{{ t('cron.zone') }}<input id="cron-zone" v-model="cronTimezone" placeholder="Asia/Shanghai" autocomplete="off" /></label>
              <button class="btn btn-primary" :disabled="busy" @click="runCronPreview">{{ t('cron.preview') }}</button>
            </div>
            <p v-if="cronError" class="notice error" role="alert">{{ t(cronError) }}</p>
            <table v-if="cronResult?.occurrences.length" class="projects-table"><thead><tr><th>{{ t('cron.utc') }}</th><th>{{ t('cron.local') }}</th><th>{{ t('cron.offset') }}</th></tr></thead>
              <tbody><tr v-for="fire in cronResult.occurrences" :key="fire.utc"><td class="mono">{{ fire.utc }}</td><td class="mono">{{ fire.local }}</td><td>{{ fire.offset }}</td></tr></tbody></table>
            <p v-if="cronResult && !cronResult.occurrences.length" class="muted">{{ t('cron.empty') }}</p>
          </div>
        </template>
        <template v-else-if="activeView === 'workflows'"><WorkflowEditor :projects="projects" :user-role="user.role" /></template>
        <template v-else-if="activeView === 'runs'"><ExecutionCenter :projects="projects" :can-operate="canOperate" /><RunPanel :projects="projects" /></template>
        <template v-else-if="activeView === 'dag-runs'"><DagRunPanel :projects="projects" :user-role="user.role" /></template>
        <template v-else-if="activeView === 'queue'"><DispatchQueuePanel :projects="projects" /></template>
        <template v-else-if="activeView === 'workers'"><WorkerPanel :admin="user.role === 'ADMIN'" /></template>
        <template v-else-if="activeView === 'settings'"><SettingsPanel :projects="projects" :admin="user.role === 'ADMIN'" /></template>
        <template v-else-if="activeView === 'audit'"><AuditPanel /></template>
      </div>
    </main>
    <div v-if="confirmLogout" class="modal-backdrop" @click.self="confirmLogout = false"><div class="modal">
      <h2>{{ t('modals.signOutTitle') }}</h2><p class="muted">{{ t('modals.signOutBody') }}</p>
      <div class="modal-actions"><button class="btn btn-outline" type="button" @click="confirmLogout = false">{{ t('modals.stay') }}</button><button class="btn btn-primary" type="button" :disabled="busy" @click="signOut">{{ t('modals.signOut') }}</button></div>
    </div></div>
    <div v-if="showJobCreate" class="modal-backdrop" @click.self="showJobCreate = false"><form class="modal" @submit.prevent="saveJob">
      <div class="modal-top"><div class="eyebrow">{{ editingJob ? t('modals.editDraft') : t('modals.newDraft') }}</div><button type="button" class="icon-btn" :aria-label="t('common.close')" @click="showJobCreate = false">×</button></div>
      <h2>{{ editingJob ? t('modals.editTitle') : t('modals.createTitle') }}</h2><p class="muted">{{ t('modals.draftHint') }}</p>
      <label for="job-name">{{ t('common.name') }}</label><input id="job-name" v-model="jobName" required maxlength="100" @input="jobPendingKey = null" />
      <label for="job-handler">{{ t('modals.handler') }}</label><input id="job-handler" v-model="jobHandler" required pattern="safe\.[a-z][a-z0-9_.-]{0,120}" :title="t('modals.handlerTitle')" @input="jobPendingKey = null" />
      <label for="job-input">{{ t('modals.parameters') }}</label><textarea id="job-input" v-model="jobParameters" rows="3" maxlength="16384" @input="jobPendingKey = null" />
      <label for="job-timeout">{{ t('modals.timeout') }}</label><input id="job-timeout" v-model.number="jobTimeout" type="number" min="1" max="86400" />
      <label for="job-attempts">{{ t('modals.attempts') }}</label><input id="job-attempts" v-model.number="jobMaxAttempts" type="number" min="1" max="10" />
      <div class="modal-actions"><button class="btn btn-outline" type="button" @click="showJobCreate = false">{{ t('common.cancel') }}</button><button class="btn btn-primary" type="submit" :disabled="busy || !activeJobProject">{{ editingJob ? t('modals.saveChanges') : t('modals.saveDraft') }}</button></div>
    </form></div>
    <div v-if="showPassword" class="modal-backdrop" @click.self="showPassword = false"><form class="modal" @submit.prevent="savePassword">
      <h2>{{ t('modals.passwordTitle') }}</h2><p class="muted">{{ t('modals.passwordHint') }}</p>
      <label for="current-password">{{ t('modals.currentPassword') }}</label><input id="current-password" v-model="currentPassword" type="password" required />
      <label for="next-password">{{ t('modals.nextPassword') }}</label><input id="next-password" v-model="nextPassword" type="password" required minlength="12" />
      <div class="modal-actions"><button class="btn btn-outline" type="button" @click="showPassword = false">{{ t('common.cancel') }}</button><button class="btn btn-primary" type="submit" :disabled="busy">{{ t('modals.updatePassword') }}</button></div>
    </form></div>
    <div v-if="showCreate" class="modal-backdrop" @click.self="showCreate = false"><form class="modal" @submit.prevent="saveProject"><div class="modal-top"><div class="eyebrow">{{ t('modals.newWorkspace') }}</div><button type="button" class="icon-btn" :aria-label="t('common.close')" @click="showCreate = false">×</button></div><h2>{{ t('modals.createProjectTitle') }}</h2><p class="muted">{{ t('modals.createProjectHint') }}</p><label for="project-name">{{ t('modals.projectName') }}</label><input id="project-name" v-model="projectName" maxlength="100" required :placeholder="t('modals.projectPlaceholder')" autofocus @input="onChangeName" /><div class="modal-actions"><button type="button" class="btn btn-outline" @click="showCreate = false">{{ t('common.cancel') }}</button><button type="submit" class="btn btn-primary" :disabled="busy || !projectName.trim()">{{ busy ? t('common.creating') : t('modals.createProject') }}</button></div></form></div>
  </div>
</template>
