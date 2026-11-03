<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AuditEvent } from './api'
import { listAudit } from './api'
import { formatWhen } from './i18n'

const { t, locale } = useI18n()
const events = ref<AuditEvent[]>([])
const error = ref('')
const busy = ref(false)

async function reload() {
  busy.value = true
  error.value = ''
  try { events.value = await listAudit() }
  catch (problem) {
    error.value = t('errors.unableAudit')
    events.value = []
  } finally { busy.value = false }
}
onMounted(() => { void reload() })
</script>

<template>
  <div class="page-title-row"><div><div class="eyebrow">{{ t('audit.eyebrow') }}</div>
    <h1>{{ t('audit.title') }}</h1>
    <p class="muted">{{ t('audit.hint') }}</p></div>
  </div>
  <div class="panel table-panel">
    <div class="panel-heading"><div><h3>{{ t('audit.latest') }} <span class="count-tag">{{ events.length }}</span></h3></div>
      <button class="btn btn-outline" type="button" :disabled="busy" @click="reload">{{ busy ? t('common.loading') : t('common.refresh') }}</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <table v-if="events.length" class="projects-table"><thead><tr><th>{{ t('audit.when') }}</th><th>{{ t('audit.actor') }}</th><th>{{ t('audit.action') }}</th><th>{{ t('audit.project') }}</th><th>{{ t('audit.outcome') }}</th></tr></thead>
      <tbody><tr v-for="event in events" :key="event.createdAt + event.action + event.actor">
        <td class="mono" :title="event.createdAt">{{ formatWhen(event.createdAt, locale, true) }}</td><td>{{ event.actor }}</td><td>{{ event.action }}</td>
        <td class="mono">{{ event.projectId || '—' }}</td><td>{{ event.outcome }}</td>
      </tr></tbody>
    </table>
    <div v-else class="empty-state"><h3>{{ t('audit.emptyTitle') }}</h3><p>{{ t('audit.emptyBody') }}</p></div>
  </div>
</template>
