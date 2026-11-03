<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { AuditEvent } from './api'
import { listAudit } from './api'

const events = ref<AuditEvent[]>([])
const error = ref('')
const busy = ref(false)

async function reload() {
  busy.value = true
  error.value = ''
  try { events.value = await listAudit() }
  catch (problem) {
    error.value = problem instanceof Error ? problem.message : 'Unable to read audit'
    events.value = []
  } finally { busy.value = false }
}
onMounted(() => { void reload() })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">AUDIT</div>
    <h1>Change history</h1>
    <p class="muted">Actor, action, project and outcome only. Payloads and secrets are not stored here.</p></div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>Latest events <span class="count-tag">{{ events.length }}</span></h3></div>
      <button class="btn btn-outline" type="button" :disabled="busy" @click="reload">{{ busy ? 'Loading...' : 'Refresh' }}</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="events.length" class="projects-table"><thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Project</th><th>Outcome</th></tr></thead>
      <tbody><tr v-for="event in events" :key="event.createdAt + event.action + event.actor">
        <td class="mono">{{ event.createdAt }}</td><td>{{ event.actor }}</td><td>{{ event.action }}</td>
        <td class="mono">{{ event.projectId || '—' }}</td><td>{{ event.outcome }}</td>
      </tr></tbody>
    </table>
    <div v-else class="empty-state"><h3>No audit events</h3><p>Job changes recorded after this version appear here.</p></div>
  </div>
</template>
