<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DispatchAttempt, JobRun, JobRunDetail, Project } from './api'
import { cancelJobRun, jobRunDetail, jobRunLogs, listJobRuns, retryJobRun } from './api'

const props = defineProps<{ projects: Project[]; canOperate: boolean }>()
const { t } = useI18n()
const projectId = ref('')
const rows = ref<JobRun[]>([])
const selected = ref('')
const detail = ref<JobRunDetail | null>(null)
const logs = ref<{ message: string; level: string; createdAt: string }[]>([])
const error = ref('')
const busy = ref(false)
const jobId = ref('')
const origin = ref('')
const status = ref('')
const worker = ref('')
const from = ref('')
const to = ref('')
const copied = ref('')
let timer = 0

function filters(): Record<string, string> {
  return {
    jobId: jobId.value.trim(),
    origin: origin.value,
    status: status.value,
    worker: worker.value.trim(),
    from: from.value ? new Date(from.value).toISOString() : '',
    to: to.value ? new Date(to.value).toISOString() : '',
  }
}
function duration(run: JobRun) {
  if (!run.startedAt || !run.finishedAt) return '—'
  const millis = Date.parse(run.finishedAt) - Date.parse(run.startedAt)
  if (!Number.isFinite(millis) || millis < 0) return '—'
  return `${Math.round(millis / 1000)}s`
}
async function reload() {
  if (!projectId.value) { rows.value = []; return }
  try {
    rows.value = await listJobRuns(projectId.value, filters())
    error.value = ''
    if (selected.value) await open(selected.value)
  } catch {
    error.value = t('errors.unableRuns')
  }
}
async function open(runId: string) {
  selected.value = runId
  if (!projectId.value) return
  detail.value = await jobRunDetail(projectId.value, runId)
  const page = await jobRunLogs(projectId.value, runId)
  logs.value = page.items ?? []
}
async function copy(value: string) {
  await navigator.clipboard.writeText(value)
  copied.value = value
}
async function cancel(run: JobRun) {
  if (!props.canOperate || busy.value) return
  busy.value = true
  try {
    await cancelJobRun(projectId.value, run.id, crypto.randomUUID(), run.runVersion)
    await reload()
  } catch {
    error.value = t('errors.stopFailed')
  } finally { busy.value = false }
}
async function retry(run: JobRun) {
  if (!props.canOperate || busy.value) return
  busy.value = true
  try {
    await retryJobRun(projectId.value, run.id, crypto.randomUUID())
    await reload()
  } catch {
    error.value = t('errors.retryFailed')
  } finally { busy.value = false }
}
function attemptEnd(attempt: DispatchAttempt) {
  return attempt.finishedAt ?? t('execution.stillOpen')
}
watch(() => props.projects, () => {
  if (!props.projects.some(item => item.id === projectId.value)) {
    projectId.value = props.projects[0]?.id ?? ''
    selected.value = ''
    detail.value = null
    logs.value = []
  }
}, { immediate: true })
watch(projectId, () => { selected.value = ''; detail.value = null; logs.value = []; void reload() })
timer = window.setInterval(() => { if (projectId.value && document.visibilityState === 'visible') void reload() }, 3000)
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">{{ t('execution.eyebrow') }}</div><h1>{{ t('execution.title') }}</h1>
    <p class="muted">{{ t('execution.hint') }}</p></div>
    <select v-model="projectId" :aria-label="t('execution.project')"><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select>
  </div>
  <div class="panel">
    <div class="cron-form">
      <label>{{ t('execution.jobId') }}<input v-model="jobId" :placeholder="t('execution.jobPlaceholder')" /></label>
      <label>{{ t('execution.origin') }}<select v-model="origin"><option value="">{{ t('common.any') }}</option><option>CRON</option><option>MANUAL</option><option>RETRY</option></select></label>
      <label>{{ t('execution.status') }}<select v-model="status"><option value="">{{ t('common.any') }}</option><option>WAITING_DISPATCH</option><option>DISPATCHED</option><option>CANCEL_REQUESTED</option><option>CANCELLED</option><option>SUCCEEDED</option><option>FAILED</option></select></label>
      <label>{{ t('execution.worker') }}<input v-model="worker" :placeholder="t('execution.workerPlaceholder')" /></label>
      <label>{{ t('execution.from') }}<input v-model="from" type="datetime-local" /></label>
      <label>{{ t('execution.to') }}<input v-model="to" type="datetime-local" /></label>
      <button class="btn btn-outline" type="button" @click="reload">{{ t('execution.apply') }}</button>
    </div>
  </div>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <div class="panel table-panel">
    <table v-if="rows.length" class="projects-table"><thead><tr><th>{{ t('execution.colRun') }}</th><th>{{ t('execution.colJob') }}</th><th>{{ t('execution.colOrigin') }}</th><th>{{ t('execution.colStatus') }}</th><th>{{ t('execution.colFailure') }}</th><th>{{ t('execution.colDuration') }}</th><th></th></tr></thead>
      <tbody><tr v-for="run in rows" :key="run.id">
        <td class="mono">{{ run.id.slice(0, 8) }}</td><td class="mono">{{ run.jobId.slice(0, 8) }} r{{ run.jobRevision }}</td>
        <td>{{ run.origin }}</td><td>{{ run.status }}</td><td>{{ run.failureCode || '—' }}</td><td>{{ duration(run) }}</td>
        <td>
          <button class="btn btn-outline" @click="open(run.id)">{{ t('common.details') }}</button>
          <button class="btn btn-outline" @click="copy(run.id)">{{ copied === run.id ? t('common.copied') : t('common.copyId') }}</button>
          <button class="btn btn-outline" :disabled="!canOperate || busy || ['SUCCEEDED','FAILED','CANCELLED'].includes(run.status)" @click="cancel(run)">{{ t('execution.stop') }}</button>
          <button class="btn btn-outline" :disabled="!canOperate || busy || !['FAILED','CANCELLED','SUCCEEDED'].includes(run.status)" @click="retry(run)">{{ t('execution.again') }}</button>
        </td>
      </tr></tbody></table>
    <p v-else class="muted">{{ t('execution.empty') }}</p>
  </div>
  <div v-if="detail" class="panel">
    <h3>{{ t('execution.runTitle', { id: detail.run.id }) }}</h3>
    <p class="muted">{{ t('execution.created', { time: detail.run.createdAt, control: detail.run.controlState, version: detail.run.runVersion }) }}</p>
    <p v-if="detail.task">{{ t('execution.queue', { id: detail.task.id, status: detail.task.status, worker: detail.task.workerId || t('execution.notLeased'), attempts: detail.task.attempts }) }}</p>
    <p v-else class="muted">{{ t('execution.noTask') }}</p>
    <table v-if="detail.attempts.length" class="projects-table"><thead><tr><th>{{ t('execution.epoch') }}</th><th>{{ t('execution.worker') }}</th><th>{{ t('execution.status') }}</th><th>{{ t('execution.started') }}</th><th>{{ t('execution.finished') }}</th></tr></thead>
      <tbody><tr v-for="attempt in detail.attempts" :key="`${attempt.taskId}-${attempt.leaseEpoch}`">
        <td>{{ attempt.leaseEpoch }}</td><td class="mono">{{ attempt.workerId }}</td><td>{{ attempt.status }}</td>
        <td>{{ attempt.startedAt }}</td><td>{{ attemptEnd(attempt) }}</td>
      </tr></tbody></table>
    <p v-else class="muted">{{ t('execution.noAttempt') }}</p>
    <h3>{{ t('execution.logs') }}</h3>
    <p v-if="!logs.length" class="muted">{{ t('execution.noLogs') }}</p>
    <p v-for="(line, index) in logs" :key="index" class="mono">{{ line.createdAt }} {{ line.level }} {{ line.message }}</p>
  </div>
</template>
