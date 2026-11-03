<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Job, Schedule, ScheduleTrigger, ApiError } from './api'
import { archiveSchedule, createSchedule, listSchedules, listScheduleTriggers, runScheduleNow, toggleSchedule, updateSchedule } from './api'
const props = defineProps<{ projectId: string; jobs: Job[] }>()
const { t } = useI18n()
const rows = ref<Schedule[]>([])
const selectedJob = ref('')
const expression = ref('*/5 * * * * *')
const timeZone = ref('Asia/Shanghai')
const misfirePolicy = ref<'SKIP' | 'FIRE_ONCE' | 'CATCH_UP'>('FIRE_ONCE')
const showForm = ref(false)
const pendingKey = ref('')
const triggers = ref<ScheduleTrigger[]>([])
const selectedScheduleId = ref('')
const error = ref('')
const busy = ref(false)
const candidates = () => props.jobs.filter(j => j.publishedRevision > 0)
function fail(key: string, status?: number) {
  error.value = t(key)
  if (status === 409) pendingKey.value = ''
}
async function refresh() {
  if (!props.projectId) { rows.value = []; return }
  try { rows.value = await listSchedules(props.projectId); error.value = '' }
  catch { error.value = t('schedule.queryFailed') }
}
watch(() => props.projectId, () => { selectedScheduleId.value = ''; triggers.value = []; void refresh() }, { immediate: true })
watch(() => props.jobs, () => { if (!candidates().some(j => j.id === selectedJob.value)) selectedJob.value = candidates()[0]?.id ?? '' })
function changed() { pendingKey.value = '' }
async function create() {
  const job = candidates().find(j => j.id === selectedJob.value)
  if (!job || busy.value) return
  busy.value = true; error.value = ''
  if (!pendingKey.value) pendingKey.value = crypto.randomUUID()
  try {
    await createSchedule(props.projectId, {
      jobId: job.id, jobRevision: job.publishedRevision, expression: expression.value,
      timeZone: timeZone.value, misfirePolicy: misfirePolicy.value
    }, pendingKey.value)
    showForm.value = false; pendingKey.value = ''
    await refresh()
  } catch (e) { fail('schedule.createFailed', (e as ApiError).status) }
  finally { busy.value = false }
}
async function toggle(row: Schedule) {
  if (busy.value) return
  busy.value = true
  try { await toggleSchedule(props.projectId, row.id, row.version, !row.enabled); await refresh() }
  catch { error.value = t('schedule.toggleFailed') }
  finally { busy.value = false }
}
async function edit(row: Schedule) {
  if (busy.value) return
  busy.value = true
  try {
    await updateSchedule(props.projectId, row, {
      jobId: row.jobId, jobRevision: row.jobRevision, expression: expression.value,
      timeZone: timeZone.value, misfirePolicy: misfirePolicy.value
    })
    await refresh()
  } catch { error.value = t('schedule.updateFailed') }
  finally { busy.value = false }
}
async function archive(row: Schedule) {
  if (busy.value) return
  busy.value = true
  try { await archiveSchedule(props.projectId, row); await refresh() }
  catch { error.value = t('schedule.archiveFailed') }
  finally { busy.value = false }
}
async function runOnce(row: Schedule) {
  if (busy.value) return
  busy.value = true
  try { await runScheduleNow(props.projectId, row.id, crypto.randomUUID()) }
  catch { error.value = t('schedule.runFailed') }
  finally { busy.value = false }
}
async function showTriggers(row: Schedule) {
  selectedScheduleId.value = row.id
  try { triggers.value = await listScheduleTriggers(props.projectId, row.id) }
  catch { error.value = t('schedule.triggersFailed') }
}
</script>

<template>
  <section class="panel cron-panel" :aria-label="t('schedule.aria')">
    <div class="panel-heading"><div><h3>{{ t('schedule.title') }}</h3><p>{{ t('schedule.hint') }}</p></div>
      <button class="btn btn-primary" :disabled="!candidates().length || !projectId" @click="showForm = !showForm">{{ t('schedule.new') }}</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <form v-if="showForm" class="cron-form" @submit.prevent="create">
      <label for="schedule-job">{{ t('schedule.publishedJob') }}<select id="schedule-job" v-model="selectedJob" required @change="changed"><option v-for="job in candidates()" :key="job.id" :value="job.id">{{ t('schedule.jobOption', { name: job.name, revision: job.publishedRevision }) }}</option></select></label>
      <label for="schedule-cron">{{ t('schedule.cron') }}<input id="schedule-cron" v-model="expression" maxlength="120" required @input="changed" /></label>
      <label for="schedule-zone">{{ t('schedule.zone') }}<input id="schedule-zone" v-model="timeZone" maxlength="64" required @input="changed" /></label>
      <label for="schedule-misfire">{{ t('schedule.misfire') }}<select id="schedule-misfire" v-model="misfirePolicy" @change="changed"><option value="SKIP">{{ t('schedule.skip') }}</option><option value="FIRE_ONCE">{{ t('schedule.fireOnce') }}</option><option value="CATCH_UP">{{ t('schedule.catchUp') }}</option></select></label>
      <button class="btn btn-primary" type="submit" :disabled="busy || !selectedJob">{{ busy ? t('common.saving') : t('schedule.save') }}</button>
    </form>
    <div class="table-panel">
      <table v-if="rows.length" class="projects-table"><thead><tr><th>{{ t('schedule.colRevision') }}</th><th>{{ t('schedule.colCron') }}</th><th>{{ t('schedule.colNext') }}</th><th>{{ t('common.state') }}</th><th>{{ t('common.actions') }}</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id"><td class="mono">{{ row.jobId.slice(0,8) }} · r{{ row.jobRevision }}</td>
          <td class="mono">{{ row.expression }} / {{ row.timeZone }}</td><td class="mono">{{ row.nextFireUtc }}</td>
          <td>{{ row.enabled ? t('schedule.enabled') : t('schedule.paused') }}</td><td>
            <button class="btn btn-outline" :disabled="busy" @click="toggle(row)">{{ row.enabled ? t('schedule.pause') : t('schedule.resume') }}</button>
            <button class="btn btn-outline" :disabled="busy" @click="edit(row)">{{ t('schedule.saveForm') }}</button>
            <button class="btn btn-outline" :disabled="busy" @click="runOnce(row)">{{ t('schedule.runOnce') }}</button>
            <button class="btn btn-outline" :disabled="busy" @click="archive(row)">{{ t('common.archive') }}</button>
            <button class="btn btn-outline" @click="showTriggers(row)">{{ t('schedule.fireRecords') }}</button></td></tr>
      </tbody></table><p v-else class="muted">{{ t('schedule.empty') }}</p>
    </div>
    <div v-if="selectedScheduleId" class="table-panel">
      <h4>{{ t('schedule.fireTitle') }}</h4>
      <table v-if="triggers.length" class="projects-table"><thead><tr><th>{{ t('schedule.colInstant') }}</th><th>{{ t('schedule.colTrigger') }}</th><th>{{ t('schedule.colStatus') }}</th></tr></thead><tbody>
        <tr v-for="record in triggers" :key="record.id"><td class="mono">{{ record.plannedFireUtc }}</td><td class="mono">{{ record.id.slice(0,8) }}</td><td>{{ record.status }}</td></tr>
      </tbody></table><p v-else class="muted">{{ t('schedule.noFires') }}</p>
    </div>
  </section>
</template>
