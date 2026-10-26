<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Job, Schedule, ScheduleTrigger, ApiError } from './api'
import { createSchedule, listSchedules, listScheduleTriggers, toggleSchedule } from './api'
const props = defineProps<{ projectId: string; jobs: Job[] }>()
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
async function refresh() {
  if (!props.projectId) { rows.value = []; return }
  try { rows.value = await listSchedules(props.projectId); error.value = '' }
  catch (e) { error.value = (e as ApiError).message || 'Unable to query Scheduler RPC' }
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
  } catch (e) {
    error.value = (e as ApiError).message || 'Schedule creation failed'
    if ((e as ApiError).status === 409) pendingKey.value = ''
  } finally { busy.value = false }
}
async function toggle(row: Schedule) {
  if (busy.value) return
  busy.value = true
  try { await toggleSchedule(props.projectId, row.id, row.version, !row.enabled); await refresh() }
  catch (e) { error.value = (e as ApiError).message || 'Failed to update schedule' }
  finally { busy.value = false }
}
async function showTriggers(row: Schedule) {
  selectedScheduleId.value = row.id
  try { triggers.value = await listScheduleTriggers(props.projectId, row.id) }
  catch (e) { error.value = (e as ApiError).message || 'Unable to query triggers' }
}
</script>

<template>
  <section class="panel cron-panel" aria-label="Persisted Cron schedules">
    <div class="panel-heading"><div><h3>Persistent Cron schedules</h3><p>Scheduler creates durable fire records. Engine / Worker execution is not available yet.</p></div>
      <button class="btn btn-primary" :disabled="!candidates().length || !projectId" @click="showForm = !showForm">+ New schedule</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <form v-if="showForm" class="cron-form" @submit.prevent="create">
      <label for="schedule-job">Published job<select id="schedule-job" v-model="selectedJob" required @change="changed"><option v-for="job in candidates()" :key="job.id" :value="job.id">{{ job.name }} — revision {{ job.publishedRevision }}</option></select></label>
      <label for="schedule-cron">Six-field Cron<input id="schedule-cron" v-model="expression" maxlength="120" required @input="changed" /></label>
      <label for="schedule-zone">IANA time zone<input id="schedule-zone" v-model="timeZone" maxlength="64" required @input="changed" /></label>
      <label for="schedule-misfire">Misfire policy<select id="schedule-misfire" v-model="misfirePolicy" @change="changed"><option value="SKIP">Skip old</option><option value="FIRE_ONCE">Fire once</option><option value="CATCH_UP">Bounded catch-up</option></select></label>
      <button class="btn btn-primary" type="submit" :disabled="busy || !selectedJob">{{ busy ? 'Saving...' : 'Save schedule' }}</button>
    </form>
    <div class="table-panel">
      <table v-if="rows.length" class="projects-table"><thead><tr><th>Job revision</th><th>Cron / Zone</th><th>Next UTC fire</th><th>State</th><th>Actions</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id"><td class="mono">{{ row.jobId.slice(0,8) }} · r{{ row.jobRevision }}</td>
          <td class="mono">{{ row.expression }} / {{ row.timeZone }}</td><td class="mono">{{ row.nextFireUtc }}</td>
          <td>{{ row.enabled ? 'Enabled' : 'Paused' }}</td><td><button class="btn btn-outline" :disabled="busy" @click="toggle(row)">{{ row.enabled ? 'Pause' : 'Resume' }}</button> <button class="btn btn-outline" @click="showTriggers(row)">Fire records</button></td></tr>
      </tbody></table><p v-else class="muted">No schedules saved yet. Publish a job to enable scheduling.</p>
    </div>
    <div v-if="selectedScheduleId" class="table-panel">
      <h4>Committed fire records — Engine delivery pending</h4>
      <table v-if="triggers.length" class="projects-table"><thead><tr><th>UTC fire instant</th><th>Logical trigger</th><th>Status</th></tr></thead><tbody>
        <tr v-for="record in triggers" :key="record.id"><td class="mono">{{ record.plannedFireUtc }}</td><td class="mono">{{ record.id.slice(0,8) }}</td><td>{{ record.status }}</td></tr>
      </tbody></table><p v-else class="muted">No committed fire records for this schedule.</p>
    </div>
  </section>
</template>
