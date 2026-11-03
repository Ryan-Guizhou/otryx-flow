<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WorkerDrain, WorkerRegistration } from './api'
import { drainWorker, listDrains, listWorkerRegistrations } from './api'

const props = defineProps<{ admin: boolean }>()
const { t } = useI18n()
const drains = ref<WorkerDrain[]>([])
const registrations = ref<WorkerRegistration[]>([])
const workerId = ref('')
const seconds = ref(60)
const error = ref('')
const busy = ref(false)

async function reload() {
  busy.value = true
  error.value = ''
  try {
    drains.value = await listDrains()
    registrations.value = await listWorkerRegistrations().catch(() => [])
  }
  catch (problem) {
    error.value = t('errors.unableDrains')
    drains.value = []
  } finally { busy.value = false }
}

async function drain() {
  if (!props.admin || !workerId.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    await drainWorker(workerId.value.trim(), seconds.value)
    workerId.value = ''
    await reload()
  } catch (problem) {
    error.value = t('errors.unableDrain')
  } finally { busy.value = false }
}

onMounted(() => { void reload() })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">{{ t('workers.eyebrow') }}</div>
    <h1>{{ t('workers.title') }}</h1>
    <p class="muted">{{ t('workers.hint') }}</p></div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>{{ t('workers.active') }} <span class="count-tag">{{ drains.length }}</span></h3>
      <p>{{ t('workers.activeHint') }}</p></div>
      <button class="btn btn-outline" type="button" :disabled="busy" @click="reload">{{ busy ? t('common.loading') : t('common.refresh') }}</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="drains.length" class="projects-table"><thead><tr><th>{{ t('workers.colWorker') }}</th><th>{{ t('workers.colUntil') }}</th></tr></thead>
      <tbody><tr v-for="row in drains" :key="row.workerId"><td class="mono">{{ row.workerId }}</td><td class="mono">{{ row.drainUntil }}</td></tr></tbody>
    </table>
    <div v-else class="empty-state"><h3>{{ t('workers.emptyTitle') }}</h3><p>{{ t('workers.emptyBody') }}</p></div>
    <h3>{{ t('workers.heartbeats') }} <span class="count-tag">{{ registrations.length }}</span></h3>
    <table v-if="registrations.length" class="projects-table"><thead><tr><th>{{ t('workers.colWorker') }}</th><th>{{ t('workers.colStatus') }}</th><th>{{ t('workers.colHandlers') }}</th><th>{{ t('workers.colSlots') }}</th></tr></thead>
      <tbody><tr v-for="row in registrations" :key="row.workerId"><td class="mono">{{ row.workerId }}</td><td>{{ row.status }}</td><td class="mono">{{ row.handlersCsv }}</td><td>{{ row.occupied }}/{{ row.capacity }}</td></tr></tbody>
    </table>
    <p v-else class="muted">{{ t('workers.noHeartbeat') }}</p>
    <form v-if="admin" class="cron-form" @submit.prevent="drain">
      <label>{{ t('workers.workerId') }}<input v-model="workerId" pattern="[A-Za-z0-9_.-]{3,64}" required /></label>
      <label>{{ t('workers.seconds') }}<input v-model.number="seconds" type="number" min="1" max="3600" /></label>
      <button class="btn btn-primary" type="submit" :disabled="busy">{{ t('workers.drain') }}</button>
    </form>
  </div>
</template>
