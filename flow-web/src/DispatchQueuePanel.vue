<script setup lang="ts">
import { ref, watch } from 'vue'
import type { DispatchTask, Project } from './api'
import { listDispatchTasks } from './api'

const props = defineProps<{ projects: Project[] }>()
const projectId = ref('')
const tasks = ref<DispatchTask[]>([])
const busy = ref(false)
const error = ref('')

async function reload() {
  if (!projectId.value) { tasks.value = []; return }
  busy.value = true
  error.value = ''
  try { tasks.value = await listDispatchTasks(projectId.value) }
  catch (problem) { error.value = problem instanceof Error ? problem.message : 'Unable to query Dispatcher'; tasks.value = [] }
  finally { busy.value = false }
}
watch(() => props.projects, list => {
  if (!list.some(project => project.id === projectId.value)) projectId.value = list[0]?.id ?? ''
}, { immediate: true })
watch(projectId, () => { void reload() }, { immediate: true })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">PERSISTED DISPATCH</div>
    <h1>Task queue</h1>
    <p class="muted">Real Dispatcher state from OTRYX RPC. A lease indicates ownership, not handler success.</p></div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>Dispatch tasks <span class="count-tag">{{ tasks.length }}</span></h3>
      <p>Claim, heartbeat and completion are reserved for trusted Worker RPC clients in the next milestone.</p></div>
      <select v-model="projectId" aria-label="Project for queue">
        <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
      <button class="btn btn-outline" type="button" :disabled="busy || !projectId" @click="reload">
        {{ busy ? 'Loading...' : 'Refresh' }}
      </button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="tasks.length" class="projects-table">
      <thead><tr><th>Task / Engine run</th><th>Handler</th><th>Attempts</th><th>Lease epoch</th><th>State</th></tr></thead>
      <tbody><tr v-for="task in tasks" :key="task.id">
        <td class="mono">{{ task.id }}<small class="muted">{{ task.runId }}</small></td>
        <td class="mono">{{ task.handlerKey }}</td>
        <td>{{ task.attempts }} / {{ task.maxAttempts }}</td>
        <td>{{ task.leaseEpoch }}</td>
        <td><span class="pill-grey">{{ task.status }}</span></td>
      </tr></tbody>
    </table>
    <div v-else-if="!busy" class="empty-state"><h3>No dispatch tasks yet</h3>
      <p>Schedule a published Job to create durable Engine runs, then wait for Dispatcher enqueue.</p></div>
  </div>
</template>
