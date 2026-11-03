<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DispatchTask, Project } from './api'
import { listDispatchTasks } from './api'

const props = defineProps<{ projects: Project[] }>()
const { t } = useI18n()
const projectId = ref('')
const tasks = ref<DispatchTask[]>([])
const busy = ref(false)
const error = ref('')

async function reload() {
  if (!projectId.value) { tasks.value = []; return }
  busy.value = true
  error.value = ''
  try { tasks.value = await listDispatchTasks(projectId.value) }
  catch { error.value = t('errors.unableDispatcher'); tasks.value = [] }
  finally { busy.value = false }
}
watch(() => props.projects, list => {
  if (!list.some(project => project.id === projectId.value)) projectId.value = list[0]?.id ?? ''
}, { immediate: true })
watch(projectId, () => { void reload() }, { immediate: true })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">{{ t('queue.eyebrow') }}</div>
    <h1>{{ t('queue.title') }}</h1>
    <p class="muted">{{ t('queue.hint') }}</p></div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>{{ t('queue.heading') }} <span class="count-tag">{{ tasks.length }}</span></h3>
      <p>{{ t('queue.headingHint') }}</p></div>
      <select v-model="projectId" :aria-label="t('queue.project')">
        <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
      <button class="btn btn-outline" type="button" :disabled="busy || !projectId" @click="reload">
        {{ busy ? t('common.loading') : t('common.refresh') }}
      </button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="tasks.length" class="projects-table">
      <thead><tr><th>{{ t('queue.colTask') }}</th><th>{{ t('queue.colHandler') }}</th><th>{{ t('queue.colAttempts') }}</th><th>{{ t('queue.colEpoch') }}</th><th>{{ t('queue.colState') }}</th></tr></thead>
      <tbody><tr v-for="task in tasks" :key="task.id">
        <td class="mono">{{ task.id }}<small class="muted">{{ task.runId }}</small></td>
        <td class="mono">{{ task.handlerKey }}</td>
        <td>{{ task.attempts }} / {{ task.maxAttempts }}</td>
        <td>{{ task.leaseEpoch }}</td>
        <td><span class="pill-grey">{{ task.status }}</span></td>
      </tr></tbody>
    </table>
    <div v-else-if="!busy" class="empty-state"><h3>{{ t('queue.emptyTitle') }}</h3>
      <p>{{ t('queue.emptyBody') }}</p></div>
  </div>
</template>
