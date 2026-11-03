<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Project, ProjectAdmission } from './api'
import { getAdmission, saveAdmission } from './api'

const props = defineProps<{ projects: Project[]; admin: boolean }>()
const projectId = ref('')
const form = ref<ProjectAdmission | null>(null)
const error = ref('')
const busy = ref(false)
const saved = ref(false)

async function reload() {
  saved.value = false
  if (!projectId.value) { form.value = null; return }
  busy.value = true
  error.value = ''
  try { form.value = await getAdmission(projectId.value) }
  catch (problem) {
    error.value = problem instanceof Error ? problem.message : 'Unable to load admission'
    form.value = null
  } finally { busy.value = false }
}

async function save() {
  if (!form.value || !props.admin) return
  busy.value = true
  error.value = ''
  saved.value = false
  try {
    form.value = await saveAdmission(projectId.value, {
      maxInflight: form.value.maxInflight,
      maxQueued: form.value.maxQueued,
      concurrencyPolicy: form.value.concurrencyPolicy,
      retentionDays: form.value.retentionDays
    })
    saved.value = true
  } catch (problem) {
    error.value = problem instanceof Error ? problem.message : 'Unable to save admission'
  } finally { busy.value = false }
}

watch(() => props.projects, list => {
  if (!list.some(project => project.id === projectId.value)) projectId.value = list[0]?.id ?? ''
}, { immediate: true })
watch(projectId, () => { void reload() }, { immediate: true })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">ADMISSION</div>
    <h1>Project limits</h1>
    <p class="muted">Dispatcher enforces these limits. A missing row uses the built-in bounded default.</p></div>
  </div>
  <div class="panel">
    <div class="panel-heading"><div><h3>Quota</h3><p>ALLOW stops at inflight. QUEUE waits up to the queue cap. FORBID keeps one active task per job.</p></div>
      <select v-model="projectId" aria-label="Project for admission">
        <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <form v-if="form" class="cron-form" @submit.prevent="save">
      <label>Max inflight<input v-model.number="form.maxInflight" type="number" min="1" max="256" :disabled="!admin" /></label>
      <label>Max queued<input v-model.number="form.maxQueued" type="number" min="1" max="1024" :disabled="!admin" /></label>
      <label>Policy<select v-model="form.concurrencyPolicy" :disabled="!admin"><option>ALLOW</option><option>FORBID</option><option>QUEUE</option></select></label>
      <label>Retention days<input v-model.number="form.retentionDays" type="number" min="1" max="3650" :disabled="!admin" /></label>
      <button class="btn btn-primary" type="submit" :disabled="busy || !admin">{{ busy ? 'Saving...' : 'Save limits' }}</button>
    </form>
    <p v-if="saved" class="muted">Saved.</p>
    <p v-if="!admin" class="muted">This account can read limits. Changes require an administrator.</p>
  </div>
</template>
