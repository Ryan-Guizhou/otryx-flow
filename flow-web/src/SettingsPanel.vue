<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Project, ProjectAdmission } from './api'
import { getAdmission, saveAdmission } from './api'

const props = defineProps<{ projects: Project[]; admin: boolean }>()
const { t } = useI18n()
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
    error.value = t('errors.unableAdmission')
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
    error.value = t('errors.unableSaveAdmission')
  } finally { busy.value = false }
}

watch(() => props.projects, list => {
  if (!list.some(project => project.id === projectId.value)) projectId.value = list[0]?.id ?? ''
}, { immediate: true })
watch(projectId, () => { void reload() }, { immediate: true })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">{{ t('limits.eyebrow') }}</div>
    <h1>{{ t('limits.title') }}</h1>
    <p class="muted">{{ t('limits.hint') }}</p></div>
  </div>
  <div class="panel">
    <div class="panel-heading"><div><h3>{{ t('limits.quota') }}</h3><p>{{ t('limits.quotaHint') }}</p></div>
      <select v-model="projectId" :aria-label="t('limits.project')">
        <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <form v-if="form" class="cron-form" @submit.prevent="save">
      <label>{{ t('limits.maxInflight') }}<input v-model.number="form.maxInflight" type="number" min="1" max="256" :disabled="!admin" /></label>
      <label>{{ t('limits.maxQueued') }}<input v-model.number="form.maxQueued" type="number" min="1" max="1024" :disabled="!admin" /></label>
      <label>{{ t('limits.policy') }}<select v-model="form.concurrencyPolicy" :disabled="!admin"><option>ALLOW</option><option>FORBID</option><option>QUEUE</option></select></label>
      <label>{{ t('limits.retention') }}<input v-model.number="form.retentionDays" type="number" min="1" max="3650" :disabled="!admin" /></label>
      <button class="btn btn-primary" type="submit" :disabled="busy || !admin">{{ busy ? t('common.saving') : t('limits.save') }}</button>
    </form>
    <p v-if="saved" class="muted">{{ t('common.saved') }}</p>
    <p v-if="!admin" class="muted">{{ t('limits.readOnly') }}</p>
  </div>
</template>
