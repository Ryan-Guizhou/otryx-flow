<script setup lang="ts">
import { ref, watch } from 'vue'
import type { EngineRun, Project } from './api'
import { listRuns } from './api'

const props = defineProps<{ projects: Project[] }>()
const projectId = ref('')
const runs = ref<EngineRun[]>([])
const busy = ref(false)
const error = ref('')

async function reload() {
  if (!projectId.value) { runs.value = []; return }
  busy.value = true
  error.value = ''
  try { runs.value = await listRuns(projectId.value) }
  catch (problem) {
    error.value = problem instanceof Error ? problem.message : 'Unable to query Engine'
    runs.value = []
  } finally { busy.value = false }
}

watch(() => props.projects, (projects) => {
  if (!projects.some(project => project.id === projectId.value)) {
    projectId.value = projects[0]?.id ?? ''
  }
}, { immediate: true })
watch(projectId, () => { void reload() }, { immediate: true })
</script>

<template>
  <div class="page-title-row">
    <div><div class="eyebrow">PERSISTED EXECUTIONS</div><h1>Engine runs</h1>
      <p class="muted">Records are created from real Scheduler Outbox deliveries via OTRYX RPC and Engine Inbox.</p>
    </div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>Logical runs <span class="count-tag">{{ runs.length }}</span></h3>
      <p>WAITING_DISPATCH means durable acceptance, not actual Worker execution.</p></div>
      <select v-model="projectId" aria-label="Project for Engine runs">
        <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
      <button class="btn btn-outline" type="button" :disabled="busy || !projectId" @click="reload">{{ busy ? 'Loading...' : 'Refresh' }}</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="runs.length" class="projects-table"><thead><tr><th>Run</th><th>Job revision</th><th>Scheduled (UTC)</th><th>State</th></tr></thead>
      <tbody><tr v-for="run in runs" :key="run.id"><td><strong class="mono">{{ run.id }}</strong><small class="muted"> {{ run.eventId }}</small></td>
        <td class="mono">{{ run.jobId }} / r{{ run.jobRevision }}</td><td class="mono">{{ run.plannedFireUtc }}</td>
        <td><span class="pill-grey">{{ run.status }}</span></td></tr></tbody></table>
    <div v-else-if="!busy" class="empty-state"><h3>No Engine runs yet</h3>
      <p>Create a Cron schedule under Jobs and wait for a real Trigger to be accepted. Worker execution arrives in a later milestone.</p>
    </div>
  </div>
</template>
