<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { EngineRun, Project } from './api'
import { listRuns } from './api'

const props = defineProps<{ projects: Project[] }>()
const { t } = useI18n()
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
    error.value = t('errors.unableEngine')
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
    <div><div class="eyebrow">{{ t('runs.eyebrow') }}</div><h1>{{ t('runs.title') }}</h1>
      <p class="muted">{{ t('runs.hint') }}</p>
    </div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>{{ t('runs.logical') }} <span class="count-tag">{{ runs.length }}</span></h3>
      <p>{{ t('runs.logicalHint') }}</p></div>
      <select v-model="projectId" :aria-label="t('runs.project')">
        <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
      <button class="btn btn-outline" type="button" :disabled="busy || !projectId" @click="reload">{{ busy ? t('common.loading') : t('common.refresh') }}</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="runs.length" class="projects-table"><thead><tr><th>{{ t('runs.colRun') }}</th><th>{{ t('runs.colRevision') }}</th><th>{{ t('runs.colScheduled') }}</th><th>{{ t('runs.colState') }}</th></tr></thead>
      <tbody><tr v-for="run in runs" :key="run.id"><td><strong class="mono">{{ run.id }}</strong><small class="muted"> {{ run.eventId }}</small></td>
        <td class="mono">{{ run.jobId }} / r{{ run.jobRevision }}</td><td class="mono">{{ run.plannedFireUtc }}</td>
        <td><span class="pill-grey">{{ run.status }}</span></td></tr></tbody></table>
    <div v-else-if="!busy" class="empty-state"><h3>{{ t('runs.emptyTitle') }}</h3>
      <p>{{ t('runs.emptyBody') }}</p>
    </div>
  </div>
</template>
