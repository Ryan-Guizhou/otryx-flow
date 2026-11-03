<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { WorkerDrain } from './api'
import { drainWorker, listDrains } from './api'

const props = defineProps<{ admin: boolean }>()
const drains = ref<WorkerDrain[]>([])
const workerId = ref('')
const seconds = ref(60)
const error = ref('')
const busy = ref(false)

async function reload() {
  busy.value = true
  error.value = ''
  try { drains.value = await listDrains() }
  catch (problem) {
    error.value = problem instanceof Error ? problem.message : 'Unable to list drains'
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
    error.value = problem instanceof Error ? problem.message : 'Unable to drain worker'
  } finally { busy.value = false }
}

onMounted(() => { void reload() })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">WORKERS</div>
    <h1>Worker drain</h1>
    <p class="muted">This page lists drain windows stored by Dispatcher. It does not invent heartbeat or free slots.</p></div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>Active drains <span class="count-tag">{{ drains.length }}</span></h3>
      <p>A drained worker stops claiming new tasks. Leases it already holds stay fenced by epoch.</p></div>
      <button class="btn btn-outline" type="button" :disabled="busy" @click="reload">{{ busy ? 'Loading...' : 'Refresh' }}</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="drains.length" class="projects-table"><thead><tr><th>Worker</th><th>Drain until</th></tr></thead>
      <tbody><tr v-for="row in drains" :key="row.workerId"><td class="mono">{{ row.workerId }}</td><td class="mono">{{ row.drainUntil }}</td></tr></tbody>
    </table>
    <div v-else class="empty-state"><h3>No worker is draining</h3><p>Heartbeat inventory is not a separate registry.</p></div>
    <form v-if="admin" class="cron-form" @submit.prevent="drain">
      <label>Worker id<input v-model="workerId" pattern="[A-Za-z0-9_.-]{3,64}" required /></label>
      <label>Seconds<input v-model.number="seconds" type="number" min="1" max="3600" /></label>
      <button class="btn btn-primary" type="submit" :disabled="busy">Drain</button>
    </form>
  </div>
</template>
