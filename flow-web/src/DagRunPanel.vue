<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  ApiError,
  DagControlAudit,
  DagNode,
  DagRun,
  DagRunDetail,
  DispatchLogPage,
  DispatchLogView,
  Project,
} from './api'
import {
  cancelDagRun,
  getDagRun,
  getWorkflowRevision,
  getDagRunLogs,
  listDagControlHistory,
  listDagRuns,
  retryDagNode,
} from './api'

const props = defineProps<{ projects: Project[]; userRole: string }>()
const projectId = ref('')
const runs = ref<DagRun[]>([])
const selectedRunId = ref('')
const detail = ref<DagRunDetail | null>(null)
const history = ref<DagControlAudit[]>([])
const logs = ref<DispatchLogView[]>([])
const logCursor = ref<string | null>(null)
const graphNodes = ref<Array<{ id: string; type: string; label?: string }>>([])
const graphEdges = ref<Array<{ id: string; source: string; target: string; branch: string }>>([])
const graphError = ref('')
const statusFilter = ref('ALL')
const currentPage = ref(1)
const pageSize = 10
const activeTab = ref<'topology' | 'nodes' | 'history' | 'logs'>('topology')
const busy = ref(false)
const detailBusy = ref(false)
const logBusy = ref(false)
const error = ref('')
const logError = ref('')
const statusMessage = ref('')
const cancelRequest = ref<{ key: string; version: number; runId: string } | null>(null)
const retryKeys = ref<Record<string, { key: string; version: number; runId: string }>>({})
const isAdmin = computed(() => props.userRole === 'ADMIN')
const selectedRun = computed(() => runs.value.find(run => run.id === selectedRunId.value) ?? detail.value?.run ?? null)
const filteredRuns = computed(() => statusFilter.value === 'ALL'
  ? runs.value
  : runs.value.filter(run => run.status === statusFilter.value))
const pageCount = computed(() => Math.max(1, Math.ceil(filteredRuns.value.length / pageSize)))
const pagedRuns = computed(() => filteredRuns.value.slice((currentPage.value - 1) * pageSize,
  currentPage.value * pageSize))
const latestNodes = computed(() => {
  const byNode = new Map<string, DagNode>()
  for (const node of detail.value?.nodes ?? []) {
    const previous = byNode.get(node.nodeId)
    if (!previous || node.logicalAttempt > previous.logicalAttempt) byNode.set(node.nodeId, node)
  }
  return [...byNode.values()].sort((a, b) => a.nodeId.localeCompare(b.nodeId))
})
const statuses = computed(() => [...new Set(runs.value.map(run => run.status))].sort())

function newKey(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : String(Date.now()) + '-' + Math.random().toString(36).slice(2)
}
function apiError(problem: unknown): ApiError {
  if (problem instanceof Error) return problem as ApiError
  return new Error('Unexpected workflow-run error') as ApiError
}
function messageForError(problem: unknown): string {
  const e = apiError(problem)
  if (e.status === 401) return 'Your session expired. Sign in again to continue.'
  if (e.status === 403) return 'This action is not permitted for your role.'
  if (e.status === 404) return 'The Run or project is no longer available.'
  if (e.status === 409) return 'The Run changed concurrently. Current server state has been refreshed.'
  if (e.status === 502 || e.status === 503) return 'The execution service is temporarily unavailable. Retry without changing the request key.'
  return e.message || 'Workflow-run request failed.'
}
function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
function duration(run: DagRun): string {
  const start = Date.parse(run.createdAt)
  const end = Date.parse(run.updatedAt)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '—'
  const seconds = Math.floor((end - start) / 1000)
  if (seconds < 60) return seconds + 's'
  if (seconds < 3600) return Math.floor(seconds / 60) + 'm ' + seconds % 60 + 's'
  return Math.floor(seconds / 3600) + 'h ' + Math.floor((seconds % 3600) / 60) + 'm'
}
function latestAttemptNumber(nodeId: string): number {
  return Math.max(0, ...(detail.value?.nodes ?? []).filter(node => node.nodeId === nodeId).map(node => node.logicalAttempt))
}
function attemptsFor(nodeId: string): DagNode[] {
  return (detail.value?.nodes ?? []).filter(node => node.nodeId === nodeId)
    .sort((a, b) => a.logicalAttempt - b.logicalAttempt)
}
function controlAvailable(): boolean {
  return !!projectId.value && !!detail.value && !detailBusy.value && !busy.value
}

async function reloadRuns(): Promise<void> {
  if (!projectId.value) {
    runs.value = []
    selectedRunId.value = ''
    detail.value = null
    history.value = []
    logs.value = []
    logCursor.value = null
    graphNodes.value = []
    graphEdges.value = []
    graphError.value = ''
    return
  }
  busy.value = true
  error.value = ''
  try {
    runs.value = (await listDagRuns(projectId.value))
      .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    currentPage.value = 1
    if (selectedRunId.value && !runs.value.some(run => run.id === selectedRunId.value)) {
      selectedRunId.value = ''
      detail.value = null
      history.value = []
      logs.value = []
    }
  } catch (problem) {
    error.value = messageForError(problem)
  } finally {
    busy.value = false
  }
}
async function reloadDetail(): Promise<void> {
  if (!projectId.value || !selectedRunId.value) return
  detailBusy.value = true
  error.value = ''
  statusMessage.value = ''
  try {
    const current = await getDagRun(projectId.value, selectedRunId.value)
    detail.value = current
    graphNodes.value = []
    graphEdges.value = []
    graphError.value = ''
    const tasks: Promise<unknown>[] = [
      listDagControlHistory(projectId.value, selectedRunId.value).then(value => { history.value = value })
        .catch(problem => { error.value = 'Run loaded, but control history could not be read: ' + messageForError(problem) }),
      reloadLogs(true),
    ]
    await Promise.all(tasks)
    try {
      const revision = await getWorkflowRevision(projectId.value,
        current.run.workflowId, current.run.workflowRevision)
      if (revision.graphSha256 !== current.run.graphSha256) {
        throw new Error('Immutable workflow revision hash does not match this Run.')
      }
      const graph = JSON.parse(revision.graphJson) as {
        nodes: Array<{ id: string; type: string; label?: string }>
        edges: Array<{ id: string; source: string; target: string; branch: string }>
      }
      if (!Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) {
        throw new Error('Immutable workflow graph is invalid.')
      }
      graphNodes.value = graph.nodes
      graphEdges.value = graph.edges
    } catch (problem) {
      graphError.value = messageForError(problem)
    }
    const index = runs.value.findIndex(run => run.id === current.run.id)
    if (index >= 0 && detail.value) runs.value[index] = detail.value.run
  } catch (problem) {
    error.value = messageForError(problem)
  } finally {
    detailBusy.value = false
  }
}
async function reloadLogs(reset: boolean): Promise<void> {
  if (!projectId.value || !selectedRunId.value) return
  if (reset) {
    logs.value = []
    logCursor.value = null
  }
  logBusy.value = true
  logError.value = ''
  try {
    const page: DispatchLogPage = await getDagRunLogs(projectId.value, selectedRunId.value, reset ? null : logCursor.value, 100)
    logs.value = reset ? page.items : [...logs.value, ...page.items]
    logCursor.value = page.nextCursor
  } catch (problem) {
    logError.value = messageForError(problem)
  } finally {
    logBusy.value = false
  }
}
async function selectRun(id: string): Promise<void> {
  selectedRunId.value = id
  detail.value = null
  history.value = []
  logs.value = []
  activeTab.value = 'topology'
  cancelRequest.value = null
  retryKeys.value = {}
  graphNodes.value = []
  graphEdges.value = []
  graphError.value = ''
  await reloadDetail()
}
async function refreshAll(): Promise<void> {
  await reloadRuns()
  if (selectedRunId.value) await reloadDetail()
}
async function cancelSelectedRun(): Promise<void> {
  if (!isAdmin.value || !controlAvailable() || !detail.value) return
  const run = detail.value.run
  const pending = cancelRequest.value
  if (!pending && run.status !== 'RUNNING') return
  if (!pending && !window.confirm('Request cancellation for this workflow run? In-flight external side effects cannot be rolled back.')) return
  const request = pending && pending.runId === run.id
    ? pending : { key: newKey(), version: run.runVersion, runId: run.id }
  cancelRequest.value = request
  busy.value = true
  error.value = ''
  statusMessage.value = ''
  try {
    await cancelDagRun(projectId.value, request.runId, request.version, request.key)
    cancelRequest.value = null
    statusMessage.value = 'Cancellation requested. The Run remains CANCEL_REQUESTED until Dispatcher fencing converges.'
    await refreshAll()
  } catch (problem) {
    const e = apiError(problem)
    error.value = messageForError(problem)
    if (e.status === 409) {
      cancelRequest.value = null
      await reloadDetail()
      error.value = messageForError(problem)
    }
  } finally {
    busy.value = false
  }
}
async function retryNode(node: DagNode): Promise<void> {
  if (!isAdmin.value || !controlAvailable() || !detail.value) return
  const run = detail.value.run
  const pending = retryKeys.value[node.nodeId]
  if (!pending && (run.status !== 'FAILED' || node.status !== 'FAILED'
      || node.logicalAttempt !== latestAttemptNumber(node.nodeId) || node.logicalAttempt >= 3)) return
  const request = pending && pending.runId === run.id
    ? pending : { key: newKey(), version: run.runVersion, runId: run.id }
  retryKeys.value = { ...retryKeys.value, [node.nodeId]: request }
  busy.value = true
  error.value = ''
  statusMessage.value = ''
  try {
    await retryDagNode(projectId.value, request.runId, node.nodeId, request.version, request.key)
    const next = { ...retryKeys.value }
    delete next[node.nodeId]
    retryKeys.value = next
    statusMessage.value = 'A new logical NodeRun was accepted. Previous attempts remain immutable.'
    await refreshAll()
  } catch (problem) {
    const e = apiError(problem)
    error.value = messageForError(problem)
    if (e.status === 409) {
      const next = { ...retryKeys.value }
      delete next[node.nodeId]
      retryKeys.value = next
      await reloadDetail()
    }
  } finally {
    busy.value = false
  }
}
watch(() => props.projects.map(project => project.id).join('|'), () => {
  if (!props.projects.length) {
    projectId.value = ''
    void reloadRuns()
    return
  }
  if (!projectId.value || !props.projects.some(project => project.id === projectId.value)) {
    projectId.value = props.projects[0]?.id ?? ''
  }
}, { immediate: true })
watch(statusFilter, () => { currentPage.value = 1 })
watch(projectId, () => {
  currentPage.value = 1
  selectedRunId.value = ''
  detail.value = null
  history.value = []
  logs.value = []
  logCursor.value = null
  graphNodes.value = []
  graphEdges.value = []
  graphError.value = ''
  cancelRequest.value = null
  retryKeys.value = {}
  void reloadRuns()
})
</script>

<template>
  <div class="page-title-row">
    <div>
      <div class="eyebrow">DURABLE DAG EXECUTIONS</div>
      <h1>Workflow runs</h1>
      <p class="muted">Run and NodeRun state comes from Engine storage; logs are separately persisted by Dispatcher.</p>
    </div>
    <button class="btn btn-outline" type="button" :disabled="busy || detailBusy || !projectId" @click="refreshAll">
      {{ busy || detailBusy ? 'Refreshing...' : 'Refresh' }}
    </button>
  </div>

  <div class="panel table-panel">
    <div class="panel-heading">
      <div><h3>DAG Runs <span class="count-tag">{{ filteredRuns.length }}</span></h3><p>Fixed workflow revisions; CANCEL_REQUESTED is not the same as CANCELLED.</p></div>
      <select v-model="projectId" aria-label="Project for workflow runs" :disabled="busy || detailBusy">
        <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
      </select>
      <select v-model="statusFilter" aria-label="Filter workflow run status">
        <option value="ALL">All statuses</option>
        <option v-for="status in statuses" :key="status" :value="status">{{ status }}</option>
      </select>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="statusMessage" class="notice" role="status">{{ statusMessage }}</p>
    <table v-if="filteredRuns.length" class="projects-table">
      <thead><tr><th>Run</th><th>Revision</th><th>State</th><th>Duration</th><th>Updated</th><th></th></tr></thead>
      <tbody>
        <tr v-for="run in pagedRuns" :key="run.id">
          <td><strong class="mono">{{ run.id }}</strong><small class="muted">{{ run.workflowId }}</small></td>
          <td>r{{ run.workflowRevision }}<small class="muted mono">{{ run.graphSha256.slice(0, 12) }}</small></td>
          <td><span class="pill-grey">{{ run.status }}</span></td>
          <td>{{ duration(run) }}</td>
          <td>{{ formatTime(run.updatedAt) }}</td>
          <td><button class="btn btn-outline" type="button" :disabled="detailBusy" @click="selectRun(run.id)">Details</button></td>
        </tr>
      </tbody>
    </table>
    <div v-else-if="!busy" class="empty-state">
      <h3>{{ runs.length ? 'No Runs match this status' : 'No workflow runs yet' }}</h3>
      <p>{{ runs.length ? 'Choose a different status filter.' : 'Publish a workflow revision in the Workflows canvas and start a run from that fixed revision.' }}</p>
    </div>
    <div v-if="filteredRuns.length" class="dag-run-pagination">
      <small>Page {{ currentPage }} of {{ pageCount }}</small>
      <button class="btn btn-outline" type="button" :disabled="currentPage <= 1" @click="currentPage--">Previous</button>
      <button class="btn btn-outline" type="button" :disabled="currentPage >= pageCount" @click="currentPage++">Next</button>
    </div>
  </div>

  <div v-if="selectedRunId" class="panel dag-run-detail">
    <div class="dag-run-heading">
      <div><div class="eyebrow">RUN DETAILS</div><h2>{{ detail?.run.status ?? 'Loading...' }}</h2><p class="mono muted">{{ selectedRunId }}</p></div>
      <div class="dag-run-actions">
        <button v-if="isAdmin && (detail?.run.status === 'RUNNING' || (cancelRequest && cancelRequest.runId === detail?.run.id))" class="btn btn-outline" type="button" :disabled="busy || detailBusy" @click="cancelSelectedRun">{{ cancelRequest ? 'Retry same cancellation request' : 'Request cancellation' }}</button>
        <span v-else-if="detail?.run.status === 'CANCEL_REQUESTED'" class="pill-grey">Cancellation in progress</span>
        <button class="btn btn-outline" type="button" :disabled="busy || detailBusy" @click="reloadDetail">Refresh details</button>
      </div>
    </div>
    <p v-if="detailBusy" class="muted" role="status">Loading authoritative Engine state...</p>
    <template v-if="detail">
      <div class="dag-run-meta">
        <div><span>Workflow</span><strong>{{ detail.run.workflowId }}</strong></div>
        <div><span>Revision</span><strong>r{{ detail.run.workflowRevision }}</strong></div>
        <div><span>Duration</span><strong>{{ duration(detail.run) }}</strong></div>
        <div><span>Run version</span><strong>v{{ detail.run.runVersion }}</strong></div>
        <div class="dag-hash"><span>Graph SHA-256</span><code>{{ detail.run.graphSha256 }}</code></div>
      </div>
      <div class="dag-run-tabs" role="tablist" aria-label="Run detail sections">
        <button type="button" role="tab" :aria-selected="activeTab === 'topology'" :class="{active:activeTab === 'topology'}" @click="activeTab = 'topology'">Immutable topology</button>
        <button type="button" role="tab" :aria-selected="activeTab === 'nodes'" :class="{active:activeTab === 'nodes'}" @click="activeTab = 'nodes'">Node attempts</button>
        <button type="button" role="tab" :aria-selected="activeTab === 'history'" :class="{active:activeTab === 'history'}" @click="activeTab = 'history'">Control history</button>
        <button type="button" role="tab" :aria-selected="activeTab === 'logs'" :class="{active:activeTab === 'logs'}" @click="activeTab = 'logs'; if (!logs.length && !logBusy) reloadLogs(true)">Execution logs</button>
      </div>

      <section v-if="activeTab === 'topology'">
        <p v-if="graphError" class="notice error" role="alert">{{ graphError }}</p>
        <div v-else-if="graphNodes.length" class="dag-topology">
          <article v-for="node in graphNodes" :key="node.id" class="dag-topology-node">
            <div><strong>{{ node.label ?? node.id }}</strong><small class="muted mono">{{ node.id }} · {{ node.type }}</small></div>
            <span class="pill-grey">{{ latestNodes.find(item => item.nodeId === node.id)?.status ?? 'BLOCKED' }}</span>
            <div class="dag-topology-edges">
              <span v-for="edge in graphEdges.filter(item => item.source === node.id)" :key="edge.id">
                {{ edge.branch }} → {{ edge.target }}
              </span>
              <small v-if="!graphEdges.some(item => item.source === node.id)">Terminal node</small>
            </div>
          </article>
        </div>
        <div v-else class="empty-state"><h3>Immutable topology unavailable</h3><p>The published revision could not be verified against the Run SHA-256.</p></div>
      </section>

      <section v-else-if="activeTab === 'nodes'">
        <table v-if="detail.nodes.length" class="projects-table">
          <thead><tr><th>Node</th><th>Type</th><th>Logical attempt</th><th>State</th><th>Condition</th><th>Updated</th><th></th></tr></thead>
          <tbody>
            <tr v-for="node in detail.nodes" :key="node.id">
              <td><strong>{{ node.nodeId }}</strong><small class="muted mono">{{ node.id }}</small></td>
              <td>{{ node.type }}</td>
              <td>{{ node.logicalAttempt }}</td>
              <td><span class="pill-grey">{{ node.status }}</span></td>
              <td>{{ node.conditionDecision === null ? '—' : node.conditionDecision ? 'TRUE' : 'FALSE' }}</td>
              <td>{{ formatTime(node.updatedAt) }}</td>
              <td>
                <button v-if="isAdmin && ((detail.run.status === 'FAILED' && node.status === 'FAILED' && node.logicalAttempt === latestAttemptNumber(node.nodeId) && node.logicalAttempt < 3) || retryKeys[node.nodeId]?.runId === detail.run.id)"
                  class="btn btn-outline" type="button" :disabled="busy || detailBusy" @click="retryNode(node)">{{ retryKeys[node.nodeId] ? 'Retry same request' : 'Retry logical attempt' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state"><h3>No node attempts</h3><p>The Engine has not materialized node execution state for this Run.</p></div>
      </section>

      <section v-else-if="activeTab === 'history'">
        <table v-if="history.length" class="projects-table">
          <thead><tr><th>Action</th><th>Node</th><th>Version</th><th>New NodeRun</th><th>Time</th></tr></thead>
          <tbody><tr v-for="(event, index) in history" :key="event.action + ':' + event.createdAt + ':' + index">
            <td>{{ event.action }}</td><td>{{ event.nodeId ?? '—' }}</td><td>v{{ event.oldVersion }} → v{{ event.newVersion }}</td>
            <td class="mono">{{ event.nodeRunId ?? '—' }}</td><td>{{ formatTime(event.createdAt) }}</td>
          </tr></tbody>
        </table>
        <div v-else class="empty-state"><h3>No control history</h3><p>No cancellation or manual retry has been recorded for this Run.</p></div>
      </section>

      <section v-else>
        <p v-if="logError" class="notice error" role="alert">{{ logError }}</p>
        <table v-if="logs.length" class="projects-table">
          <thead><tr><th>Time</th><th>Level</th><th>NodeRun</th><th>Message</th><th>Fields</th></tr></thead>
          <tbody><tr v-for="log in logs" :key="log.id">
            <td>{{ formatTime(log.createdAt) }}</td><td><span class="pill-grey">{{ log.level }}</span></td>
            <td class="mono">{{ log.nodeRunId }}</td><td class="dag-log-message">{{ log.message }}</td>
            <td><pre class="dag-log-fields">{{ log.fieldsJson }}</pre></td>
          </tr></tbody>
        </table>
        <div v-else-if="!logBusy" class="empty-state"><h3>No execution logs</h3><p>Worker logs are bounded and redacted on the server before they are stored or returned.</p></div>
        <div class="dag-log-footer">
          <span class="muted">{{ logs.length }} log record(s) loaded</span>
          <button v-if="logCursor" class="btn btn-outline" type="button" :disabled="logBusy" @click="reloadLogs(false)">{{ logBusy ? 'Loading...' : 'Load more' }}</button>
          <button v-else class="btn btn-outline" type="button" :disabled="logBusy || !selectedRunId" @click="reloadLogs(true)">{{ logBusy ? 'Loading...' : 'Refresh logs' }}</button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dag-run-detail{margin-top:18px;padding:20px}
.dag-run-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;flex-wrap:wrap}
.dag-run-heading h2{margin:5px 0;font-size:20px}.dag-run-heading p{margin:0;font-size:11px}
.dag-run-actions{display:flex;gap:8px;flex-wrap:wrap}
.dag-run-meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:18px 0;padding:14px;background:#f8fafc;border-radius:10px}
.dag-run-meta>div{display:flex;flex-direction:column;gap:5px;min-width:0}.dag-run-meta span{font-size:10px;color:#7c8ca1;text-transform:uppercase;letter-spacing:.5px}
.dag-run-meta strong,.dag-run-meta code{font-size:11px;overflow-wrap:anywhere}.dag-hash{grid-column:1/-1}
.dag-run-tabs{display:flex;gap:6px;border-bottom:1px solid #e6ebf1;margin-bottom:14px;flex-wrap:wrap}
.dag-run-tabs button{border:0;background:transparent;padding:10px 13px;color:#728096;cursor:pointer;border-bottom:2px solid transparent}
.dag-run-tabs button.active{color:#3159b8;border-bottom-color:#3159b8;font-weight:600}
.dag-run-detail small{display:block;margin-top:4px;font-size:10px}
.dag-log-message{max-width:380px;overflow-wrap:anywhere}
.dag-log-fields{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font-size:10px;max-width:320px}
.dag-log-footer{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:14px}
.dag-run-pagination{display:flex;justify-content:flex-end;align-items:center;gap:8px;margin-top:12px}
.dag-run-pagination small{margin-right:auto;color:#8796a8;font-size:10px}
.dag-topology{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.dag-topology-node{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px;align-items:start;min-width:0;padding:14px;border:1px solid #e7edf5;border-radius:10px;background:#fbfcff}
.dag-topology-node strong{display:block;font-size:12px;overflow-wrap:anywhere}
.dag-topology-node small{display:block;font-size:9px;margin-top:4px;overflow-wrap:anywhere}
.dag-topology-edges{grid-column:1/-1;display:flex;flex-wrap:wrap;gap:6px}
.dag-topology-edges span{font-size:10px;color:#5d7395;background:#edf3ff;border:1px solid #dce7fa;border-radius:5px;padding:4px 6px}
.dag-topology-edges small{font-size:10px;color:#9ba7b6}
@media(max-width:640px){.dag-run-detail{padding:12px}.dag-run-meta{grid-template-columns:1fr 1fr}.dag-hash{grid-column:1/-1}.dag-log-footer{align-items:stretch;flex-direction:column}.dag-topology{grid-template-columns:1fr}.dag-run-pagination .btn{padding:8px 10px}}
</style>
