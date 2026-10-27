<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Edge as FlowEdge, Node as FlowNode, Connection } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import type {
  ApiError,
  DagRun,
  Project,
  WorkflowCondition,
  WorkflowDefinition,
  WorkflowEdge,
  WorkflowGraphV1,
  WorkflowNode,
  WorkflowRevision,
  WorkflowScalar,
} from './api'
import {
  createWorkflowDraft,
  getWorkflowRevision,
  listWorkflows,
  publishWorkflow,
  saveWorkflowDraft,
  startDagRun,
} from './api'
import {
  computeDefaultLayout,
  defaultWorkflowGraph,
  serializeWorkflowGraph,
  validateWorkflowGraph,
} from './workflowGraph'

const props = defineProps<{ projects: Project[]; userRole: string }>()
type ConditionOperator = 'equals' | 'notEquals' | 'exists' | 'in' | 'and' | 'or'
interface CanvasData { payload: WorkflowNode }
type CanvasNode = FlowNode<CanvasData> & { data: CanvasData }
type CanvasEdge = FlowEdge<{ branch: WorkflowEdge['branch'] }>
interface DraftSnapshot {
  graph: WorkflowGraphV1
  dirty: boolean
  name: string
  workflow: WorkflowDefinition | null
}

const { fitView, zoomIn, zoomOut } = useVueFlow()
const selectedProjectId = ref('')
const workflows = ref<WorkflowDefinition[]>([])
const selectedWorkflowId = ref('')
const editingWorkflow = ref<WorkflowDefinition | null>(null)
const workflowName = ref('')
const canvasNodes = shallowRef<CanvasNode[]>([])
const canvasEdges = shallowRef<CanvasEdge[]>([])
const selectedNodeId = ref<string | null>(null)
const selectedEdgeId = ref<string | null>(null)
const selectedNodeDraft = ref<WorkflowNode | null>(null)
const taskParametersText = ref('{}')
const conditionOp = ref<ConditionOperator>('exists')
const conditionPath = ref('$.field')
const conditionValueJson = ref('"value"')
const conditionValuesJson = ref('["value"]')
const conditionChildrenJson = ref('[{"op":"exists","path":"$.field"},{"op":"exists","path":"$.other"}]')
const nodeDraftDirty = ref(false)
const nodeEditorError = ref('')
const dirty = ref(false)
const viewMode = ref<'draft' | 'revision'>('draft')
const revisionNumber = ref(1)
const revisionSnapshot = ref<WorkflowRevision | null>(null)
const draftSnapshot = ref<DraftSnapshot | null>(null)
const activeLayoutScope = ref('draft')
const loadingList = ref(false)
const loadingRevision = ref(false)
const busy = ref(false)
const showCreateForm = ref(false)
const newWorkflowName = ref('')
const createRequestKey = ref<string | null>(null)
const publishRequestKey = ref<string | null>(null)
const cloneRequestKey = ref<string | null>(null)
const runRequestKey = ref<string | null>(null)
const runInputJson = ref('{}')
const runResult = ref<DagRun | null>(null)
const conflictRemote = ref<WorkflowDefinition | null>(null)
const conflictNotice = ref('')
const rebaseArmed = ref(false)
const errorMessage = ref('')
const statusMessage = ref('')

const selectedProject = computed(() => props.projects.find(project => project.id === selectedProjectId.value) ?? null)
const selectedWorkflow = computed(() => workflows.value.find(workflow => workflow.id === selectedWorkflowId.value) ?? null)
const isAdmin = computed(() => props.userRole === 'ADMIN')
const canEdit = computed(() => isAdmin.value && viewMode.value === 'draft' && !!editingWorkflow.value && !loadingRevision.value)
const hasLocalChanges = computed(() => dirty.value || nodeDraftDirty.value || !!draftSnapshot.value?.dirty)
const currentRevisionCount = computed(() => editingWorkflow.value?.publishedRevision ?? selectedWorkflow.value?.publishedRevision ?? 0)
const validationErrors = computed(() => selectedWorkflowId.value ? validateWorkflowGraph(canvasToGraph()) : [])
const graphIsValid = computed(() => validationErrors.value.length === 0)
const currentGraphJson = computed(() => serializeWorkflowGraph(canvasToGraph()))
const localConflictJson = computed(() => prettyJson(currentGraphJson.value))
const remoteConflictJson = computed(() => conflictRemote.value ? prettyJson(conflictRemote.value.graphJson) : '')
const selectedCanvasEdge = computed(() => canvasEdges.value.find(edge => edge.id === selectedEdgeId.value) ?? null)
const selectedCanvasSourceType = computed(() => {
  const edge = selectedCanvasEdge.value
  if (!edge) return null
  return canvasNodes.value.find(node => node.id === edge.source)?.data.payload.type ?? null
})
const selectedEdgeBranch = computed(() => selectedCanvasEdge.value ? String(edgeBranch(selectedCanvasEdge.value) ?? '') : '')
const canPublish = computed(() => canEdit.value && !hasLocalChanges.value && !!editingWorkflow.value
  && editingWorkflow.value.draftVersion > editingWorkflow.value.lastPublishedDraftVersion
  && graphIsValid.value)
const revisionOptions = computed(() => Array.from({ length: currentRevisionCount.value }, (_, index) => index + 1))

function newKey(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : String(Date.now()) + '-' + Math.random().toString(36).slice(2)
}

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function apiError(value: unknown): ApiError {
  if (value instanceof Error) return value as ApiError
  return new Error('Unexpected workflow error') as ApiError
}

function prettyJson(value: string): string {
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

function canvasLabel(node: WorkflowNode): string {
  const raw = node as unknown as Record<string, unknown>
  const type = raw?.type === 'CONDITION' ? '◇ Condition' : '▣ Task'
  const id = typeof raw?.id === 'string' ? raw.id : 'invalid node'
  const label = typeof raw?.label === 'string' && raw.label.trim() ? ' — ' + raw.label.trim() : ''
  return type + ' · ' + id + label
}

function edgeBranch(edge: CanvasEdge): unknown {
  return edge.data?.branch ?? edge.label
}

function canvasToGraph(): WorkflowGraphV1 {
  const nodes = canvasNodes.value.map(node => node.data.payload)
  const edges = canvasEdges.value.map(edge => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    branch: edgeBranch(edge) as WorkflowEdge['branch'],
  }))
  return {
    schemaVersion: graphSchemaVersion.value as 1,
    nodes,
    edges,
  }
}

const graphSchemaVersion = ref<unknown>(1)

function layoutStorageKey(scope: string): string {
  return 'otryx-flow:workflow-layout:' + selectedProjectId.value + ':'
    + selectedWorkflowId.value + ':' + scope
}

function readSavedPositions(scope: string): Record<string, { x: number; y: number }> {
  try {
    const raw = window.localStorage.getItem(layoutStorageKey(scope))
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    return parsed && typeof parsed === 'object' ? parsed as Record<string, { x: number; y: number }> : {}
  } catch { return {} }
}

function persistLayout(): void {
  if (typeof window === 'undefined' || !selectedWorkflowId.value) return
  const positions: Record<string, { x: number; y: number }> = {}
  for (const node of canvasNodes.value) {
    positions[node.id] = { x: node.position.x, y: node.position.y }
  }
  try { window.localStorage.setItem(layoutStorageKey(activeLayoutScope.value), JSON.stringify(positions)) } catch { /* storage is optional */ }
}

function canvasLabelForEdge(edge: CanvasEdge): string {
  const branch = edgeBranch(edge)
  return typeof branch === 'string' ? branch : 'INVALID'
}

function loadGraphJson(json: string, scope: string): void {
  activeLayoutScope.value = scope
  selectedNodeId.value = null
  selectedEdgeId.value = null
  selectedNodeDraft.value = null
  nodeDraftDirty.value = false
  nodeEditorError.value = ''
  dirty.value = false
  try {
    const parsed = JSON.parse(json) as Record<string, unknown>
    if (!parsed || !Array.isArray(parsed.nodes) || !Array.isArray(parsed.edges)) {
      throw new Error('Workflow JSON must contain nodes and edges arrays.')
    }
    graphSchemaVersion.value = parsed.schemaVersion
    const graph = {
      schemaVersion: parsed.schemaVersion as 1,
      nodes: parsed.nodes as WorkflowNode[],
      edges: parsed.edges as WorkflowEdge[],
    }
    const saved = readSavedPositions(scope)
    const defaults = computeDefaultLayout(graph)
    canvasNodes.value = graph.nodes.map((payload, index) => {
      const raw = payload as unknown as Record<string, unknown>
      const id = typeof raw?.id === 'string' && raw.id ? raw.id : 'invalid-node-' + index
      const type = raw?.type === 'CONDITION' ? 'CONDITION' : 'TASK'
      const position = saved[id] ?? defaults[id] ?? { x: 70 + (index % 4) * 260, y: 55 + Math.floor(index / 4) * 145 }
      return {
        id,
        type: 'default',
        label: canvasLabel(payload),
        position,
        class: type === 'CONDITION' ? 'workflow-node-condition' : 'workflow-node-task',
        data: { payload },
      } as CanvasNode
    })
    canvasEdges.value = graph.edges.map((payload, index) => {
      const raw = payload as unknown as Record<string, unknown>
      const id = typeof raw?.id === 'string' && raw.id ? raw.id : 'invalid-edge-' + index
      const source = typeof raw?.source === 'string' ? raw.source : 'missing-source'
      const target = typeof raw?.target === 'string' ? raw.target : 'missing-target'
      const branch = raw?.branch as WorkflowEdge['branch']
      return {
        id,
        source,
        target,
        label: canvasLabelForEdge({ id, source, target, data: { branch } } as CanvasEdge),
        data: { branch },
        markerEnd: MarkerType.ArrowClosed,
      } as CanvasEdge
    })
    errorMessage.value = ''
    void nextTick(() => fitView({ padding: 0.18 }))
  } catch (problem) {
    canvasNodes.value = []
    canvasEdges.value = []
    errorMessage.value = 'Unable to read this draft graph: ' + (problem instanceof Error ? problem.message : 'invalid JSON')
  }
}

function clearEditor(): void {
  selectedWorkflowId.value = ''
  editingWorkflow.value = null
  workflowName.value = ''
  workflows.value = workflows.value
  canvasNodes.value = []
  canvasEdges.value = []
  selectedNodeId.value = null
  selectedEdgeId.value = null
  selectedNodeDraft.value = null
  nodeDraftDirty.value = false
  dirty.value = false
  viewMode.value = 'draft'
  revisionSnapshot.value = null
  draftSnapshot.value = null
  conflictRemote.value = null
  conflictNotice.value = ''
  rebaseArmed.value = false
  statusMessage.value = ''
}

function selectWorkflowInternal(workflow: WorkflowDefinition): void {
  selectedWorkflowId.value = workflow.id
  editingWorkflow.value = workflow
  workflowName.value = workflow.name
  viewMode.value = 'draft'
  revisionSnapshot.value = null
  draftSnapshot.value = null
  conflictRemote.value = null
  conflictNotice.value = ''
  rebaseArmed.value = false
  runResult.value = null
  runRequestKey.value = null
  cloneRequestKey.value = null
  loadGraphJson(workflow.graphJson, 'draft')
}

async function loadWorkflowList(projectId: string, resetSelection: boolean): Promise<void> {
  if (!projectId) { workflows.value = []; clearEditor(); return }
  loadingList.value = true
  errorMessage.value = ''
  try {
    const result = await listWorkflows(projectId)
    workflows.value = [...result].sort((left, right) =>
      new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
    const current = workflows.value.find(workflow => workflow.id === selectedWorkflowId.value)
    if (resetSelection || !current) {
      if (workflows.value.length) selectWorkflowInternal(workflows.value[0] as WorkflowDefinition)
      else clearEditor()
    } else if (!hasLocalChanges.value && viewMode.value === 'draft') {
      const latest = current as WorkflowDefinition
      editingWorkflow.value = latest
      workflowName.value = latest.name
      loadGraphJson(latest.graphJson, 'draft')
    }
  } catch (problem) {
    errorMessage.value = messageForError(problem)
  } finally { loadingList.value = false }
}

function messageForError(problem: unknown): string {
  const error = apiError(problem)
  if (error.status === 401) return 'Your session expired. Sign in again to continue.'
  if (error.status === 403) return 'This action is not permitted for your role.'
  if (error.status === 404) return 'The workflow or project is no longer available.'
  if (error.status === 409) return 'The server draft changed. Your local graph has been preserved for comparison.'
  if (error.status === 503 || error.status === 502) return 'The metadata RPC service is temporarily unavailable. Your local changes are preserved.'
  return error.message || 'Workflow request failed.'
}

function confirmLeaveEditor(): boolean {
  if (!hasLocalChanges.value) return true
  return window.confirm('This workflow has unsaved changes. Discard them and continue?')
}

watch(() => props.projects.map(project => project.id).join('|'), () => {
  if (!props.projects.length) {
    selectedProjectId.value = ''
    workflows.value = []
    clearEditor()
    return
  }
  if (!selectedProjectId.value || !props.projects.some(project => project.id === selectedProjectId.value)) {
    selectedProjectId.value = props.projects[0]?.id ?? ''
    void loadWorkflowList(selectedProjectId.value, true)
  }
}, { immediate: true })

async function onProjectChange(event: Event): Promise<void> {
  const select = event.target as HTMLSelectElement
  const nextProjectId = select.value
  if (nextProjectId === selectedProjectId.value) return
  if (!confirmLeaveEditor()) { select.value = selectedProjectId.value; return }
  selectedProjectId.value = nextProjectId
  selectedWorkflowId.value = ''
  await loadWorkflowList(nextProjectId, true)
}

function selectWorkflowById(id: string): void {
  const workflow = workflows.value.find(item => item.id === id)
  if (!workflow || workflow.id === selectedWorkflowId.value) return
  if (!confirmLeaveEditor()) return
  selectWorkflowInternal(workflow)
}

async function refreshWorkflows(): Promise<void> {
  if (!confirmLeaveEditor()) return
  dirty.value = false
  nodeDraftDirty.value = false
  draftSnapshot.value = null
  await loadWorkflowList(selectedProjectId.value, false)
}

function upsertWorkflow(workflow: WorkflowDefinition): void {
  const next = workflows.value.filter(item => item.id !== workflow.id)
  next.push(workflow)
  workflows.value = next.sort((left, right) =>
    new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
}

function resetCreateKey(): void { createRequestKey.value = null }

async function createWorkflow(): Promise<void> {
  if (!isAdmin.value || busy.value || !selectedProjectId.value) return
  const name = newWorkflowName.value.trim()
  if (!name || name.length > 100) {
    errorMessage.value = 'Workflow name must contain 1–100 characters.'
    return
  }
  if (!confirmLeaveEditor()) return
  const requestKey = createRequestKey.value ?? newKey()
  createRequestKey.value = requestKey
  busy.value = true
  errorMessage.value = ''
  statusMessage.value = ''
  try {
    const graphJson = serializeWorkflowGraph(defaultWorkflowGraph())
    const created = await createWorkflowDraft(selectedProjectId.value, { name, graphJson }, requestKey)
    upsertWorkflow(created)
    createRequestKey.value = null
    newWorkflowName.value = ''
    showCreateForm.value = false
    selectWorkflowInternal(created)
    statusMessage.value = 'Workflow draft created. Add nodes and edges, then save and publish.'
  } catch (problem) {
    errorMessage.value = messageForError(problem)
  } finally { busy.value = false }
}

function markDirty(): void {
  if (canEdit.value) {
    dirty.value = true
    statusMessage.value = ''
  }
}

function markNodeDraftDirty(): void {
  if (canEdit.value) {
    nodeDraftDirty.value = true
    nodeEditorError.value = ''
  }
}

function restoreSelection(): void {
  canvasNodes.value = canvasNodes.value.map(node => ({ ...node, selected: node.id === selectedNodeId.value }))
  canvasEdges.value = canvasEdges.value.map(edge => ({ ...edge, selected: edge.id === selectedEdgeId.value }))
}

function loadNodeDraft(id: string): void {
  const node = canvasNodes.value.find(item => item.id === id)
  if (!node?.data?.payload) return
  selectedNodeId.value = id
  selectedEdgeId.value = null
  selectedNodeDraft.value = cloneJson(node.data.payload)
  nodeDraftDirty.value = false
  nodeEditorError.value = ''
  taskParametersText.value = node.data.payload.type === 'TASK'
    ? JSON.stringify(node.data.payload.parameters, null, 2) : '{}'
  const condition = node.data.payload.type === 'CONDITION'
    ? node.data.payload.condition as unknown as Record<string, unknown> : {}
  const op = typeof condition.op === 'string' ? condition.op : 'exists'
  conditionOp.value = (['equals', 'notEquals', 'exists', 'in', 'and', 'or'].includes(op) ? op : 'exists') as ConditionOperator
  conditionPath.value = typeof condition.path === 'string' ? condition.path : '$.field'
  conditionValueJson.value = Object.prototype.hasOwnProperty.call(condition, 'value')
    ? JSON.stringify(condition.value, null, 2) : '"value"'
  conditionValuesJson.value = Array.isArray(condition.values) ? JSON.stringify(condition.values, null, 2) : '["value"]'
  conditionChildrenJson.value = Array.isArray(condition.conditions)
    ? JSON.stringify(condition.conditions, null, 2)
    : '[{"op":"exists","path":"$.field"},{"op":"exists","path":"$.other"}]'
  restoreSelection()
}

function selectNode(id: string): void {
  if (nodeDraftDirty.value && !window.confirm('Discard unapplied node field changes?')) {
    restoreSelection()
    return
  }
  loadNodeDraft(id)
}

function onNodeClick(event: any): void {
  selectNode(event.node.id)
}

function onEdgeClick(event: any): void {
  if (nodeDraftDirty.value && !window.confirm('Discard unapplied node field changes?')) {
    restoreSelection()
    return
  }
  selectedNodeId.value = null
  selectedNodeDraft.value = null
  nodeDraftDirty.value = false
  selectedEdgeId.value = event.edge.id
  restoreSelection()
}

function onPaneClick(): void {
  if (nodeDraftDirty.value && !window.confirm('Discard unapplied node field changes?')) {
    restoreSelection()
    return
  }
  selectedNodeId.value = null
  selectedEdgeId.value = null
  selectedNodeDraft.value = null
  nodeDraftDirty.value = false
  restoreSelection()
}

function applyNodeDraft(): boolean {
  if (!selectedNodeDraft.value || !selectedNodeId.value || !canEdit.value) return true
  const oldId = selectedNodeId.value
  const draft = cloneJson(selectedNodeDraft.value)
  draft.id = typeof draft.id === 'string' ? draft.id.trim() : ''
  if (!draft.id || draft.id.length > 128 || draft.id.includes('\u0000')) {
    nodeEditorError.value = 'Node ID must contain 1–128 characters and cannot contain a null character.'
    return false
  }
  if (draft.id !== oldId && canvasNodes.value.some(node => node.id === draft.id)) {
    nodeEditorError.value = 'Another node already uses this ID.'
    return false
  }
  if (draft.label !== undefined) {
    draft.label = draft.label.trim()
    if (!draft.label) draft.label = undefined
    if (draft.label && draft.label.length > 100) {
      nodeEditorError.value = 'Node label cannot exceed 100 characters.'
      return false
    }
  }
  try {
    if (draft.type === 'TASK') {
      const parameters = JSON.parse(taskParametersText.value) as unknown
      if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) {
        nodeEditorError.value = 'Task parameters must be a JSON object.'
        return false
      }
      draft.parameters = parameters as Record<string, unknown>
    } else {
      let condition: WorkflowCondition
      if (conditionOp.value === 'and' || conditionOp.value === 'or') {
        const conditions = JSON.parse(conditionChildrenJson.value) as unknown
        condition = { op: conditionOp.value, conditions: conditions as WorkflowCondition[] }
      } else if (conditionOp.value === 'exists') {
        condition = { op: 'exists', path: conditionPath.value.trim() }
      } else if (conditionOp.value === 'in') {
        const values = JSON.parse(conditionValuesJson.value) as unknown
        condition = { op: 'in', path: conditionPath.value.trim(), values: values as WorkflowScalar[] }
      } else {
        const value = JSON.parse(conditionValueJson.value) as unknown
        condition = {
          op: conditionOp.value,
          path: conditionPath.value.trim(),
          value: value as WorkflowScalar,
        }
      }
      draft.condition = condition
    }
  } catch {
    nodeEditorError.value = 'Condition values and task parameters must be valid JSON.'
    return false
  }

  canvasNodes.value = canvasNodes.value.map(node => {
    if (node.id !== oldId) return node
    return {
      ...node,
      id: draft.id,
      label: canvasLabel(draft),
      class: draft.type === 'CONDITION' ? 'workflow-node-condition' : 'workflow-node-task',
      data: { payload: draft },
      selected: true,
    }
  })
  if (draft.id !== oldId) {
    canvasEdges.value = canvasEdges.value.map(edge => ({
      ...edge,
      source: edge.source === oldId ? draft.id : edge.source,
      target: edge.target === oldId ? draft.id : edge.target,
    }))
  }
  selectedNodeId.value = draft.id
  selectedNodeDraft.value = cloneJson(draft)
  nodeDraftDirty.value = false
  nodeEditorError.value = ''
  markDirty()
  persistLayout()
  restoreSelection()
  return true
}

function addNode(type: 'TASK' | 'CONDITION'): void {
  if (!canEdit.value) return
  if (canvasNodes.value.length >= 100) {
    errorMessage.value = 'The canvas is limited to 100 nodes.'
    return
  }
  const used = new Set(canvasNodes.value.map(node => node.id))
  let index = canvasNodes.value.length + 1
  let id = (type === 'TASK' ? 'task-' : 'condition-') + index
  while (used.has(id)) { index += 1; id = (type === 'TASK' ? 'task-' : 'condition-') + index }
  let payload: WorkflowNode
  if (type === 'TASK') {
    payload = { id, type: 'TASK', label: 'Task ' + index, handlerKey: 'safe.echo',
      parameters: {}, timeoutSeconds: 30, maxAttempts: 2 }
  } else {
    payload = { id, type: 'CONDITION', label: 'Condition ' + index,
      condition: { op: 'exists', path: '$.field' } }
  }
  const count = canvasNodes.value.length
  const node: CanvasNode = {
    id,
    type: 'default',
    label: canvasLabel(payload),
    position: { x: 70 + (count % 4) * 240, y: 55 + Math.floor(count / 4) * 145 },
    class: type === 'CONDITION' ? 'workflow-node-condition' : 'workflow-node-task',
    data: { payload },
  }
  canvasNodes.value = [...canvasNodes.value, node]
  loadNodeDraft(id)
  markDirty()
  persistLayout()
}

function onConnect(connection: Connection): void {
  if (!canEdit.value || !connection.source || !connection.target) return
  if (connection.source === connection.target) {
    errorMessage.value = 'A node cannot connect to itself.'
    return
  }
  if (canvasEdges.value.length >= 500) {
    errorMessage.value = 'The canvas is limited to 500 edges.'
    return
  }
  if (canvasEdges.value.some(edge => edge.source === connection.source && edge.target === connection.target)) {
    errorMessage.value = 'This directed connection already exists.'
    return
  }
  const source = canvasNodes.value.find(node => node.id === connection.source)
  if (!source) return
  let branch: WorkflowEdge['branch'] = 'ALWAYS'
  if (source.data.payload.type === 'CONDITION') {
    const existing = new Set(canvasEdges.value.filter(edge => edge.source === connection.source)
      .map(edge => String(edgeBranch(edge))))
    if (!existing.has('TRUE')) branch = 'TRUE'
    else if (!existing.has('FALSE')) branch = 'FALSE'
    else {
      errorMessage.value = 'A condition node already has TRUE and FALSE branches.'
      return
    }
  }
  const edge: CanvasEdge = {
    id: 'edge-' + newKey(),
    source: connection.source,
    target: connection.target,
    label: branch,
    markerEnd: MarkerType.ArrowClosed,
    data: { branch },
  }
  canvasEdges.value = [...canvasEdges.value, edge]
  selectedNodeId.value = null
  selectedNodeDraft.value = null
  selectedEdgeId.value = edge.id
  markDirty()
  restoreSelection()
}

function onEdgeBranchSelect(event: Event): void {
  changeEdgeBranch((event.target as HTMLSelectElement).value)
}

function changeEdgeBranch(value: string): void {
  if (!canEdit.value || !selectedEdgeId.value) return
  if (!['ALWAYS', 'TRUE', 'FALSE'].includes(value)) return
  const edge = canvasEdges.value.find(item => item.id === selectedEdgeId.value)
  const source = canvasNodes.value.find(node => node.id === edge?.source)
  if (!edge || !source) return
  if (source.data.payload.type === 'TASK' && value !== 'ALWAYS') {
    errorMessage.value = 'Task edges must use ALWAYS.'
    return
  }
  if (source.data.payload.type === 'CONDITION' && value === 'ALWAYS') {
    errorMessage.value = 'Condition edges must use TRUE or FALSE.'
    return
  }
  canvasEdges.value = canvasEdges.value.map(item => item.id === edge.id
    ? { ...item, label: value, data: { branch: value as WorkflowEdge['branch'] } } : item)
  markDirty()
  restoreSelection()
}

function deleteSelected(): void {
  if (!canEdit.value) return
  if (selectedNodeId.value) {
    if (nodeDraftDirty.value && !window.confirm('Discard unapplied node field changes and delete this node?')) return
    const id = selectedNodeId.value
    canvasNodes.value = canvasNodes.value.filter(node => node.id !== id)
    canvasEdges.value = canvasEdges.value.filter(edge => edge.source !== id && edge.target !== id)
    selectedNodeId.value = null
    selectedNodeDraft.value = null
    nodeDraftDirty.value = false
    markDirty()
    persistLayout()
    restoreSelection()
    return
  }
  if (selectedEdgeId.value) {
    canvasEdges.value = canvasEdges.value.filter(edge => edge.id !== selectedEdgeId.value)
    selectedEdgeId.value = null
    markDirty()
    restoreSelection()
  }
}

function onCanvasKeydown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null
  if (target?.closest('input, textarea, select, button, [contenteditable="true"]')) return
  if (event.key === 'n' || event.key === 'N') {
    event.preventDefault()
    addNode(event.shiftKey ? 'CONDITION' : 'TASK')
    return
  }
  if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    deleteSelected()
    return
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowRight' || event.key === 'ArrowUp' || event.key === 'ArrowLeft') {
    const ids = canvasNodes.value.map(node => node.id)
    if (!ids.length) return
    event.preventDefault()
    const current = selectedNodeId.value ? ids.indexOf(selectedNodeId.value) : -1
    const forward = event.key === 'ArrowDown' || event.key === 'ArrowRight'
    const next = forward ? (current + 1) % ids.length : (current <= 0 ? ids.length - 1 : current - 1)
    selectNode(ids[next] as string)
  }
}

async function captureConflict(message: string): Promise<void> {
  conflictNotice.value = message
  statusMessage.value = ''
  const localJson = serializeWorkflowGraph(canvasToGraph())
  try {
    const latest = await listWorkflows(selectedProjectId.value)
    workflows.value = [...latest].sort((left, right) =>
      new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
    const remote = latest.find(item => item.id === editingWorkflow.value?.id) ?? null
    if (remote && remote.name === workflowName.value.trim() && remote.graphJson === localJson) {
      editingWorkflow.value = remote
      workflowName.value = remote.name
      loadGraphJson(remote.graphJson, 'draft')
      conflictRemote.value = null
      conflictNotice.value = ''
      rebaseArmed.value = false
      statusMessage.value = 'The server already contains this exact draft. No duplicate write was needed.'
      return
    }
    conflictRemote.value = remote
  } catch {
    conflictRemote.value = null
  }
}

function discardLocalForRemote(): void {
  if (!conflictRemote.value) return
  const remote = conflictRemote.value
  upsertWorkflow(remote)
  selectWorkflowInternal(remote)
  conflictNotice.value = ''
  conflictRemote.value = null
  rebaseArmed.value = false
  statusMessage.value = 'Loaded the latest server draft.'
}

function rebaseLocalDraft(): void {
  if (!conflictRemote.value) return
  const remote = conflictRemote.value
  editingWorkflow.value = remote
  conflictRemote.value = null
  conflictNotice.value = ''
  rebaseArmed.value = true
  dirty.value = true
  statusMessage.value = 'Local graph retained. Saving will explicitly replace the remote draft based on version v' + remote.draftVersion + ' if it has not changed again.'
}

async function saveCurrentDraft(): Promise<void> {
  if (!canEdit.value || busy.value || !editingWorkflow.value) return
  if (nodeDraftDirty.value && !applyNodeDraft()) return
  const graph = canvasToGraph()
  const problems = validateWorkflowGraph(graph)
  if (problems.length) {
    errorMessage.value = 'Fix graph validation errors before saving.'
    return
  }
  const name = workflowName.value.trim()
  if (!name || name.length > 100) {
    errorMessage.value = 'Workflow name must contain 1–100 characters.'
    return
  }
  if (rebaseArmed.value && !window.confirm('Overwrite the server draft using version v'
      + editingWorkflow.value.draftVersion + '? Your local graph will be saved only if that version is still current.')) return
  busy.value = true
  errorMessage.value = ''
  statusMessage.value = ''
  try {
    const saved = await saveWorkflowDraft(selectedProjectId.value, editingWorkflow.value, {
      name, graphJson: serializeWorkflowGraph(graph),
    })
    upsertWorkflow(saved)
    editingWorkflow.value = saved
    workflowName.value = saved.name
    loadGraphJson(saved.graphJson, 'draft')
    conflictRemote.value = null
    conflictNotice.value = ''
    rebaseArmed.value = false
    statusMessage.value = 'Draft saved as version v' + saved.draftVersion + '.'
  } catch (problem) {
    const error = apiError(problem)
    if (error.status === 409) await captureConflict('The server draft changed. Local edits are preserved; compare both versions before retrying.')
    else if (error.status === 502 || error.status === 503) await captureConflict('The save response was lost or the RPC service is unavailable. Local edits are preserved; compare the server before retrying.')
    else errorMessage.value = messageForError(problem)
  } finally { busy.value = false }
}

async function publishCurrentDraft(): Promise<void> {
  if (!canEdit.value || !editingWorkflow.value || busy.value) return
  if (hasLocalChanges.value) {
    errorMessage.value = 'Save all local changes before publishing a revision.'
    return
  }
  const problems = validateWorkflowGraph(canvasToGraph())
  if (problems.length) {
    errorMessage.value = 'Fix graph validation errors before publishing.'
    return
  }
  const key = publishRequestKey.value ?? newKey()
  publishRequestKey.value = key
  busy.value = true
  errorMessage.value = ''
  statusMessage.value = ''
  try {
    const published = await publishWorkflow(selectedProjectId.value, editingWorkflow.value, key)
    upsertWorkflow(published)
    editingWorkflow.value = published
    workflowName.value = published.name
    draftSnapshot.value = {
      graph: cloneJson(canvasToGraph()),
      dirty: false,
      name: workflowName.value,
      workflow: published,
    }
    viewMode.value = 'revision'
    revisionNumber.value = published.publishedRevision
    publishRequestKey.value = null
    const loaded = await loadRevision(published.publishedRevision)
    statusMessage.value = loaded
      ? 'Published immutable revision r' + published.publishedRevision + '.'
      : 'Revision was published, but the snapshot could not be loaded. Retry loading the revision.'
  } catch (problem) {
    const error = apiError(problem)
    if (error.status === 409) await captureConflict('The draft version changed before publication. Local graph state is preserved.')
    else errorMessage.value = messageForError(problem)
  } finally { busy.value = false }
}

async function loadRevision(revision: number): Promise<boolean> {
  if (!selectedProjectId.value || !selectedWorkflowId.value || revision < 1) return false
  loadingRevision.value = true
  errorMessage.value = ''
  try {
    const loaded = await getWorkflowRevision(selectedProjectId.value, selectedWorkflowId.value, revision)
    revisionSnapshot.value = loaded
    revisionNumber.value = revision
    runResult.value = null
    runRequestKey.value = null
    cloneRequestKey.value = null
    loadGraphJson(loaded.graphJson, 'revision-' + revision)
    return true
  } catch (problem) {
    errorMessage.value = messageForError(problem)
    return false
  } finally { loadingRevision.value = false }
}

async function enterRevisionMode(revision: number): Promise<void> {
  if (!editingWorkflow.value || revision < 1) return
  if (viewMode.value === 'draft') {
    if (nodeDraftDirty.value && !applyNodeDraft()) return
    draftSnapshot.value = {
      graph: cloneJson(canvasToGraph()),
      dirty: dirty.value,
      name: workflowName.value,
      workflow: editingWorkflow.value,
    }
  }
  viewMode.value = 'revision'
  if (!await loadRevision(revision)) {
    viewMode.value = 'draft'
    restoreDraftView()
  }
}

function restoreDraftView(): void {
  viewMode.value = 'draft'
  revisionSnapshot.value = null
  const snapshot = draftSnapshot.value
  if (snapshot) {
    editingWorkflow.value = snapshot.workflow
    workflowName.value = snapshot.name
    loadGraphJson(serializeWorkflowGraph(snapshot.graph), 'draft')
    dirty.value = snapshot.dirty
    draftSnapshot.value = null
  } else if (editingWorkflow.value) {
    workflowName.value = editingWorkflow.value.name
    loadGraphJson(editingWorkflow.value.graphJson, 'draft')
  }
}

async function onRevisionChange(event: Event): Promise<void> {
  const select = event.target as HTMLSelectElement
  const nextRevision = Number(select.value)
  const previous = revisionNumber.value
  if (!await loadRevision(nextRevision)) select.value = String(previous)
}

async function cloneRevisionAsDraft(): Promise<void> {
  if (!isAdmin.value || !revisionSnapshot.value || busy.value) return
  if (!confirmLeaveEditor()) return
  const key = cloneRequestKey.value ?? newKey()
  cloneRequestKey.value = key
  busy.value = true
  errorMessage.value = ''
  try {
    const copy = await createWorkflowDraft(selectedProjectId.value, {
      name: (revisionSnapshot.value.name + ' copy').slice(0, 100),
      graphJson: revisionSnapshot.value.graphJson,
    }, key)
    upsertWorkflow(copy)
    cloneRequestKey.value = null
    selectWorkflowInternal(copy)
    statusMessage.value = 'Created a separate editable draft from the immutable revision.'
  } catch (problem) {
    errorMessage.value = messageForError(problem)
  } finally { busy.value = false }
}

function resetRunKey(): void {
  runRequestKey.value = null
  runResult.value = null
}

async function runPublishedRevision(): Promise<void> {
  if (!revisionSnapshot.value || busy.value) return
  let input: unknown
  try { input = JSON.parse(runInputJson.value) } catch {
    errorMessage.value = 'Run input must be valid JSON.'
    return
  }
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    errorMessage.value = 'Run input must be a JSON object.'
    return
  }
  if (new TextEncoder().encode(JSON.stringify(input)).length > 16 * 1024) {
    errorMessage.value = 'Run input cannot exceed 16 KiB.'
    return
  }
  const key = runRequestKey.value ?? newKey()
  runRequestKey.value = key
  busy.value = true
  errorMessage.value = ''
  try {
    runResult.value = await startDagRun(selectedProjectId.value, {
      workflowId: revisionSnapshot.value.workflowId,
      workflowRevision: revisionSnapshot.value.revision,
      inputJson: JSON.stringify(input),
    }, key)
    runRequestKey.value = null
    statusMessage.value = 'Run accepted for immutable revision r' + revisionSnapshot.value.revision + '.'
  } catch (problem) {
    errorMessage.value = messageForError(problem)
  } finally { busy.value = false }
}

function nodeDragStopped(): void { persistLayout() }
</script>

<template>
  <section class="workflow-editor">
    <div class="page-title-row workflow-page-heading">
      <div>
        <div class="eyebrow">M3 · DAG ORCHESTRATION</div>
        <h1>Workflow canvas</h1>
        <p class="muted">Compose trusted task handlers and deterministic conditions, then publish an immutable revision.</p>
      </div>
      <div class="workflow-heading-actions">
        <label class="workflow-project-picker">Project
          <select :value="selectedProjectId" aria-label="Project for workflows" @change="onProjectChange">
            <option v-for="project in props.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
          </select>
        </label>
        <button class="btn btn-outline" type="button" :disabled="loadingList || !selectedProjectId" @click="refreshWorkflows">
          {{ loadingList ? 'Loading…' : 'Refresh' }}
        </button>
        <button v-if="isAdmin" class="btn btn-primary" type="button" :disabled="!selectedProjectId || busy"
          @click="showCreateForm = !showCreateForm">+ New workflow</button>
      </div>
    </div>

    <p v-if="errorMessage" class="notice error" role="alert">{{ errorMessage }}</p>
    <p v-if="statusMessage" class="notice workflow-status" role="status">{{ statusMessage }}</p>

    <div v-if="showCreateForm && isAdmin" class="panel workflow-create-form">
      <div class="workflow-create-copy">
        <strong>Create a workflow draft</strong>
        <small>Starts with one safe.echo task. Canvas coordinates are stored locally and never enter the published graph hash.</small>
      </div>
      <label>Workflow name
        <input v-model="newWorkflowName" maxlength="100" placeholder="e.g. Customer onboarding" @input="resetCreateKey" />
      </label>
      <div class="workflow-create-actions">
        <button class="btn btn-outline" type="button" @click="showCreateForm = false">Cancel</button>
        <button class="btn btn-primary" type="button" :disabled="busy || !newWorkflowName.trim()" @click="createWorkflow">
          {{ busy ? 'Creating…' : 'Create draft' }}
        </button>
      </div>
    </div>

    <div v-if="!props.projects.length" class="panel empty-state workflow-empty">
      <div class="empty-icon">◇</div><h3>Create a project first</h3>
      <p>Workflow definitions are scoped to a project and accessed through the Metadata service via OTRYX RPC.</p>
    </div>
    <div v-else class="workflow-workspace">
      <aside class="panel workflow-library" aria-label="Workflow definitions">
        <div class="workflow-library-heading">
          <div><strong>Workflows</strong><span class="count-tag">{{ workflows.length }}</span></div>
          <small>{{ selectedProject?.name ?? 'Selected project' }}</small>
        </div>
        <div v-if="loadingList" class="workflow-library-empty">Loading workflow definitions…</div>
        <button v-for="workflow in workflows" :key="workflow.id" type="button"
          class="workflow-library-item" :class="{ selected: selectedWorkflowId === workflow.id }"
          @click="selectWorkflowById(workflow.id)">
          <span class="workflow-library-icon">◇</span>
          <span class="workflow-library-text">
            <strong>{{ workflow.name }}</strong>
            <small>Draft v{{ workflow.draftVersion }} · {{ workflow.publishedRevision ? 'Published r' + workflow.publishedRevision : 'Not published' }}</small>
          </span>
          <span v-if="selectedWorkflowId === workflow.id && hasLocalChanges" class="workflow-dirty-dot" title="Unsaved changes" />
        </button>
        <div v-if="!loadingList && !workflows.length" class="workflow-library-empty">
          <p>No workflows in this project yet.</p>
          <button v-if="isAdmin" class="btn btn-outline" type="button" @click="showCreateForm = true">Create the first workflow</button>
        </div>
        <div class="workflow-library-footer">Schema v1 · up to 100 nodes / 500 edges</div>
      </aside>

      <section class="workflow-main">
        <div v-if="!editingWorkflow" class="panel empty-state workflow-empty">
          <div class="empty-icon">◇</div><h3>Select or create a workflow</h3>
          <p>Drafts are saved with optimistic version checks. Published revisions are immutable.</p>
        </div>

        <template v-else>
          <div class="panel workflow-toolbar">
            <div class="workflow-title-fields">
              <div class="workflow-title-line">
                <span class="workflow-state-icon">{{ viewMode === 'draft' ? '✎' : '◈' }}</span>
                <strong>{{ viewMode === 'draft' ? 'Draft editor' : 'Published revision viewer' }}</strong>
                <span class="pill-grey">{{ viewMode === 'draft' ? 'v' + editingWorkflow.draftVersion : 'r' + revisionNumber }}</span>
              </div>
              <input v-if="viewMode === 'draft'" v-model="workflowName" aria-label="Workflow name"
                class="workflow-name-input" maxlength="100" :disabled="!canEdit" @input="markDirty" />
              <div v-else class="workflow-revision-name">{{ revisionSnapshot?.name ?? editingWorkflow.name }}</div>
              <small class="muted">{{ editingWorkflow.id }}</small>
            </div>
            <div class="workflow-toolbar-actions">
              <template v-if="viewMode === 'draft'">
                <span class="workflow-draft-state" :class="{ invalid: validationErrors.length, dirty: hasLocalChanges }">
                  {{ hasLocalChanges ? 'Unsaved' : validationErrors.length ? 'Invalid graph' : 'Saved' }}
                </span>
                <button class="btn btn-outline" type="button" :disabled="busy || !canEdit || !hasLocalChanges"
                  @click="saveCurrentDraft">Save draft</button>
                <button class="btn btn-primary" type="button" :disabled="busy || !canPublish"
                  @click="publishCurrentDraft">Publish revision</button>
                <button v-if="editingWorkflow.publishedRevision > 0" class="btn btn-text" type="button"
                  :disabled="busy" @click="enterRevisionMode(editingWorkflow.publishedRevision)">View r{{ editingWorkflow.publishedRevision }}</button>
              </template>
              <template v-else>
                <label class="workflow-revision-picker">Revision
                  <select :value="revisionNumber" :disabled="loadingRevision || busy" @change="onRevisionChange">
                    <option v-for="revision in revisionOptions" :key="revision" :value="revision">r{{ revision }}</option>
                  </select>
                </label>
                <button v-if="isAdmin" class="btn btn-outline" type="button" :disabled="busy || !revisionSnapshot"
                  @click="cloneRevisionAsDraft">Clone as draft</button>
                <button class="btn btn-primary" type="button" :disabled="busy || !draftSnapshot"
                  @click="restoreDraftView">Back to draft</button>
              </template>
            </div>
          </div>

          <div v-if="viewMode === 'revision' && revisionSnapshot" class="panel workflow-revision-meta">
            <div><span class="eyebrow">IMMUTABLE REVISION</span><strong>r{{ revisionSnapshot.revision }} · source draft v{{ revisionSnapshot.sourceDraftVersion }}</strong>
              <small>Published {{ revisionSnapshot.publishedAt }}</small></div>
            <div class="workflow-hash"><span>SHA-256</span><code>{{ revisionSnapshot.graphSha256 }}</code></div>
          </div>

          <div v-if="viewMode === 'revision' && revisionSnapshot" class="panel workflow-run-panel">
            <div class="workflow-run-copy"><strong>Run this published revision</strong><small>Runs pin the immutable revision and never read the editable draft.</small></div>
            <label>Input JSON object
              <textarea v-model="runInputJson" rows="2" maxlength="16384" :disabled="busy"
                @input="resetRunKey" aria-label="DAG run input JSON" />
            </label>
            <button class="btn btn-primary" type="button" :disabled="busy" @click="runPublishedRevision">Start run</button>
            <div v-if="runResult" class="workflow-run-result" role="status">
              <strong>{{ runResult.status }}</strong><code>{{ runResult.id }}</code>
            </div>
          </div>

          <div class="workflow-editor-surface">
            <section class="panel workflow-canvas-panel">
              <div class="workflow-canvas-toolbar">
                <div class="workflow-canvas-actions">
                  <button v-if="canEdit" class="btn btn-outline" type="button" :disabled="canvasNodes.length >= 100" @click="addNode('TASK')">+ Task</button>
                  <button v-if="canEdit" class="btn btn-outline" type="button" :disabled="canvasNodes.length >= 100" @click="addNode('CONDITION')">+ Condition</button>
                  <span v-if="!canEdit" class="workflow-readonly-label">Read-only snapshot</span>
                </div>
                <div class="workflow-canvas-actions">
                  <button class="canvas-icon-btn" type="button" aria-label="Zoom out" @click="zoomOut()">−</button>
                  <button class="canvas-icon-btn" type="button" aria-label="Zoom in" @click="zoomIn()">+</button>
                  <button class="canvas-icon-btn" type="button" aria-label="Fit workflow to canvas" @click="fitView({ padding: 0.18 })">Fit</button>
                </div>
              </div>
              <div class="workflow-canvas" tabindex="0" aria-label="Workflow graph canvas. Press N to add a task, Shift N to add a condition, arrow keys to select nodes, and Delete to remove the selection."
                @keydown="onCanvasKeydown">
                <VueFlow v-model:nodes="canvasNodes" v-model:edges="canvasEdges"
                  :nodes-draggable="canEdit" :nodes-connectable="canEdit" :elements-selectable="true"
                  :delete-key-code="null" :min-zoom="0.25" :max-zoom="1.75"
                  :pan-on-drag="true" :zoom-on-scroll="true" :fit-view-on-init="true"
                  @connect="onConnect" @node-click="onNodeClick" @edge-click="onEdgeClick"
                  @pane-click="onPaneClick" @node-drag-stop="nodeDragStopped" />
                <div v-if="!canvasNodes.length" class="workflow-canvas-empty">
                  <strong>Empty graph</strong><span>Add a task node to begin.</span>
                </div>
              </div>
              <div class="workflow-canvas-footer">
                <span>{{ canvasNodes.length }} / 100 nodes</span><span>{{ canvasEdges.length }} / 500 edges</span>
                <span :class="['workflow-validity', validationErrors.length ? 'invalid' : 'valid']">
                  {{ validationErrors.length ? validationErrors.length + ' validation issue(s)' : 'Graph valid' }}
                </span>
                <span class="workflow-shortcuts">N Task · Shift+N Condition · Arrows Select · Delete Remove</span>
              </div>
            </section>

            <aside class="panel workflow-inspector">
              <div class="workflow-inspector-heading"><strong>Inspector</strong><small>{{ viewMode === 'draft' ? 'Draft properties' : 'Published snapshot' }}</small></div>
              <template v-if="selectedNodeDraft">
                <div class="workflow-inspector-title">
                  <span class="workflow-inspector-glyph">{{ selectedNodeDraft.type === 'CONDITION' ? '◇' : '▣' }}</span>
                  <div><strong>{{ selectedNodeDraft.type }} node</strong><small>{{ selectedNodeDraft.id }}</small></div>
                  <button v-if="canEdit" class="canvas-icon-btn danger" type="button" aria-label="Delete selected node" @click="deleteSelected">×</button>
                </div>
                <fieldset :disabled="!canEdit">
                  <label>Node ID
                    <input v-model="selectedNodeDraft.id" maxlength="128" @input="markNodeDraftDirty" />
                  </label>
                  <label>Label
                    <input v-model="selectedNodeDraft.label" maxlength="100" placeholder="Optional display label" @input="markNodeDraftDirty" />
                  </label>
                  <div class="workflow-static-field"><span>Type</span><strong>{{ selectedNodeDraft.type }}</strong><small>To change type, remove the node and add the other type.</small></div>
                  <template v-if="selectedNodeDraft.type === 'TASK'">
                    <label>Trusted handler
                      <select v-model="selectedNodeDraft.handlerKey" @change="markNodeDraftDirty">
                        <option value="safe.echo">safe.echo</option><option value="safe.fail">safe.fail</option>
                      </select>
                    </label>
                    <label>Parameters JSON object
                      <textarea v-model="taskParametersText" rows="5" maxlength="16384" spellcheck="false" @input="markNodeDraftDirty" />
                    </label>
                    <div class="workflow-number-fields">
                      <label>Timeout seconds<input v-model.number="selectedNodeDraft.timeoutSeconds" type="number" min="1" max="86400" @input="markNodeDraftDirty" /></label>
                      <label>Max attempts<input v-model.number="selectedNodeDraft.maxAttempts" type="number" min="1" max="10" @input="markNodeDraftDirty" /></label>
                    </div>
                  </template>
                  <template v-else-if="selectedNodeDraft.type === 'CONDITION'">
                    <label>Condition operator
                      <select v-model="conditionOp" @change="markNodeDraftDirty">
                        <option value="equals">equals</option><option value="notEquals">notEquals</option>
                        <option value="exists">exists</option><option value="in">in</option>
                        <option value="and">and</option><option value="or">or</option>
                      </select>
                    </label>
                    <template v-if="conditionOp !== 'and' && conditionOp !== 'or'">
                      <label>Safe JSON field path
                        <input v-model="conditionPath" placeholder="$.region" @input="markNodeDraftDirty" />
                      </label>
                    </template>
                    <label v-if="conditionOp === 'equals' || conditionOp === 'notEquals'">Scalar value (JSON)
                      <textarea v-model="conditionValueJson" rows="2" spellcheck="false" @input="markNodeDraftDirty" />
                    </label>
                    <label v-else-if="conditionOp === 'in'">Values array (JSON, 1–20 scalars)
                      <textarea v-model="conditionValuesJson" rows="3" spellcheck="false" @input="markNodeDraftDirty" />
                    </label>
                    <label v-else-if="conditionOp === 'and' || conditionOp === 'or'">Child conditions AST (JSON)
                      <textarea v-model="conditionChildrenJson" rows="6" spellcheck="false" @input="markNodeDraftDirty" />
                    </label>
                    <p class="workflow-field-hint">Only equals, notEquals, exists, in, and/or are accepted. Arbitrary scripts and SQL are never evaluated.</p>
                  </template>
                  <p v-if="nodeEditorError" class="notice error" role="alert">{{ nodeEditorError }}</p>
                  <button v-if="canEdit" class="btn btn-primary workflow-apply-node" type="button" :disabled="!nodeDraftDirty"
                    @click="applyNodeDraft">Apply node changes</button>
                  <p v-if="nodeDraftDirty" class="workflow-field-hint">Changes in this inspector are not on the canvas until applied.</p>
                </fieldset>
              </template>
              <template v-else-if="selectedCanvasEdge">
                <div class="workflow-inspector-title">
                  <span class="workflow-inspector-glyph">↳</span><div><strong>Connection</strong><small>{{ selectedCanvasEdge.source }} → {{ selectedCanvasEdge.target }}</small></div>
                  <button v-if="canEdit" class="canvas-icon-btn danger" type="button" aria-label="Delete selected edge" @click="deleteSelected">×</button>
                </div>
                <div class="workflow-static-field"><span>Edge ID</span><code>{{ selectedCanvasEdge.id }}</code></div>
                <label>Branch
                  <select :value="selectedEdgeBranch" :disabled="!canEdit" @change="onEdgeBranchSelect">
                    <option v-if="selectedCanvasSourceType === 'TASK'" value="ALWAYS">ALWAYS</option>
                    <template v-else><option value="TRUE">TRUE</option><option value="FALSE">FALSE</option></template>
                  </select>
                </label>
                <p class="workflow-field-hint">Task edges use ALWAYS. Condition nodes require exactly one TRUE and one FALSE outgoing edge.</p>
              </template>
              <div v-else class="workflow-inspector-empty">
                <div class="workflow-inspector-glyph">⌖</div><strong>Select a node or edge</strong>
                <p>Choose a node to edit its trusted handler or condition AST. Select an edge to change its branch.</p>
              </div>
            </aside>
          </div>

          <div v-if="conflictNotice" class="panel workflow-conflict-panel" role="alert">
            <div class="workflow-conflict-heading"><strong>Draft version conflict</strong><span class="pill-grey">Local changes preserved</span></div>
            <p>{{ conflictNotice }}</p>
            <div v-if="conflictRemote" class="workflow-conflict-meta">
              Server draft v{{ conflictRemote.draftVersion }} · updated {{ conflictRemote.updatedAt }}
            </div>
            <div v-if="conflictRemote" class="workflow-conflict-actions">
              <button class="btn btn-outline" type="button" @click="discardLocalForRemote">Load server version and discard local</button>
              <button class="btn btn-primary" type="button" @click="rebaseLocalDraft">Keep local and rebase on v{{ conflictRemote.draftVersion }}</button>
            </div>
            <div class="workflow-conflict-diff">
              <div><strong>Local graph (without canvas positions)</strong><pre>{{ localConflictJson }}</pre></div>
              <div><strong>Server graph</strong><pre>{{ remoteConflictJson || 'Server version could not be fetched. Refresh before retrying.' }}</pre></div>
            </div>
          </div>

          <div class="panel workflow-validation">
            <div class="workflow-validation-heading">
              <div><strong>Graph validation</strong><small>Client feedback only; Metadata validates again before persistence and publication.</small></div>
              <span :class="['workflow-validity', validationErrors.length ? 'invalid' : 'valid']">
                {{ validationErrors.length ? 'Needs attention' : 'Valid schema v1' }}
              </span>
            </div>
            <ul v-if="validationErrors.length" class="workflow-validation-list">
              <li v-for="problem in validationErrors" :key="problem">{{ problem }}</li>
            </ul>
            <p v-else class="workflow-validation-ok">No local structural or safety issues found. Published revisions remain immutable.</p>
          </div>
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.workflow-editor{display:flex;flex-direction:column;gap:16px;min-width:0}
.workflow-page-heading{align-items:flex-end;margin-bottom:4px}
.workflow-heading-actions{display:flex;align-items:flex-end;gap:8px;flex-wrap:wrap}
.workflow-project-picker,.workflow-revision-picker{display:flex;flex-direction:column;gap:5px;margin:0;font-size:10px;font-weight:800;color:#73829a}
.workflow-project-picker select,.workflow-revision-picker select{min-width:180px;border:1px solid #dfe7f1;border-radius:8px;background:#fff;padding:9px 10px;color:#30435d;font-size:12px}
.workflow-create-form{display:flex;align-items:flex-end;gap:16px;padding:18px 20px;flex-wrap:wrap}
.workflow-create-copy{display:flex;flex:1 1 220px;flex-direction:column;gap:4px}
.workflow-create-copy strong{font-size:12px}.workflow-create-copy small{font-size:10px;color:#8b98aa}
.workflow-create-form label,.workflow-run-panel label,.workflow-inspector label{display:flex;flex-direction:column;gap:6px;font-size:10px;font-weight:800;color:#52637a}
.workflow-create-form input,.workflow-inspector input,.workflow-inspector select,.workflow-inspector textarea,.workflow-run-panel textarea{width:100%;min-width:0;border:1px solid #dce5f0;border-radius:8px;padding:9px 10px;background:#fff;color:#263b56;font:inherit;font-size:12px;font-weight:500;outline:none}
.workflow-create-form input:focus,.workflow-inspector input:focus,.workflow-inspector select:focus,.workflow-inspector textarea:focus,.workflow-run-panel textarea:focus{border-color:#6688db;box-shadow:0 0 0 3px #dce7ff88}
.workflow-create-actions{display:flex;gap:8px}
.workflow-status{background:#edf8f2;border:1px solid #d5eee0;color:#2c7954}
.workflow-empty{grid-column:1/-1}
.workflow-workspace{display:grid;grid-template-columns:235px minmax(0,1fr);gap:16px;align-items:start}
.workflow-library{padding:0;overflow:hidden;min-width:0}
.workflow-library-heading{padding:18px 16px 14px;border-bottom:1px solid #edf1f6}
.workflow-library-heading>div{display:flex;align-items:center;gap:8px}.workflow-library-heading strong{font-size:13px}
.workflow-library-heading small{display:block;color:#96a3b5;font-size:10px;margin-top:5px}
.workflow-library-item{display:flex;align-items:center;gap:9px;width:100%;padding:13px 12px;border:0;border-bottom:1px solid #f0f3f7;background:#fff;text-align:left;color:#344760}
.workflow-library-item:hover,.workflow-library-item.selected{background:#f0f4ff}
.workflow-library-icon{width:29px;height:29px;display:grid;place-items:center;flex-shrink:0;border-radius:8px;background:#e9efff;color:#496ac4;font-size:17px}
.workflow-library-text{display:flex;flex-direction:column;gap:3px;min-width:0}
.workflow-library-text strong{font-size:11px;overflow-wrap:anywhere}
.workflow-library-text small{font-size:9px;color:#8c9bb0}
.workflow-dirty-dot{width:7px;height:7px;border-radius:50%;background:#d88b34;flex-shrink:0}
.workflow-library-empty{padding:24px 15px;text-align:center;font-size:11px;color:#8796aa}
.workflow-library-footer{padding:12px 14px;background:#fafbfd;color:#9aa7b8;font-size:9px}
.workflow-main{display:flex;flex-direction:column;gap:14px;min-width:0}
.workflow-toolbar{padding:17px 18px;display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap}
.workflow-title-fields{display:flex;flex-direction:column;gap:5px;min-width:200px;flex:1}
.workflow-title-line{display:flex;align-items:center;gap:8px;font-size:12px}
.workflow-state-icon{width:26px;height:26px;border-radius:8px;display:grid;place-items:center;background:#edf2ff;color:#4e70c8;font-size:15px}
.workflow-title-fields>small{font-size:9px;overflow-wrap:anywhere}
.workflow-name-input{border:1px solid transparent;border-radius:7px;background:transparent;padding:3px 5px;margin-left:-5px;color:#20334b;font-weight:800;font-size:18px;max-width:100%;outline:none}
.workflow-name-input:focus{border-color:#dbe5f7;background:#fbfcff}
.workflow-revision-name{font-size:18px;font-weight:800;color:#20334b}
.workflow-toolbar-actions{display:flex;align-items:center;gap:7px;flex-wrap:wrap}
.workflow-draft-state{font-size:9px;color:#3c8b66;background:#eaf8f0;border-radius:12px;padding:4px 8px}
.workflow-draft-state.dirty{color:#98632d;background:#fff4e5}.workflow-draft-state.invalid{color:#a34049;background:#fff0f0}
.workflow-revision-meta{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:16px 19px;flex-wrap:wrap}
.workflow-revision-meta>div:first-child{display:flex;flex-direction:column;gap:4px}.workflow-revision-meta strong{font-size:12px}.workflow-revision-meta small{font-size:10px;color:#8c9bb0}
.workflow-hash{display:flex;flex-direction:column;gap:5px;min-width:0;max-width:100%}.workflow-hash span{font-size:9px;color:#8c9bb0;text-transform:uppercase;letter-spacing:.8px}
.workflow-hash code{font-size:10px;overflow-wrap:anywhere;color:#435878}
.workflow-run-panel{display:flex;align-items:flex-end;gap:12px;padding:16px 19px;flex-wrap:wrap}
.workflow-run-copy{display:flex;flex-direction:column;gap:4px;flex:1 1 180px}.workflow-run-copy strong{font-size:12px}.workflow-run-copy small{font-size:10px;color:#8c9bb0}
.workflow-run-panel label{flex:1 1 240px}.workflow-run-panel textarea{resize:vertical;font-family:ui-monospace,monospace}
.workflow-run-result{display:flex;flex-direction:column;gap:3px;min-width:160px}.workflow-run-result strong{font-size:10px;color:#36845e}.workflow-run-result code{font-size:9px;overflow-wrap:anywhere;color:#70819a}
.workflow-editor-surface{display:grid;grid-template-columns:minmax(0,1fr) 275px;gap:14px;align-items:start}
.workflow-canvas-panel{padding:0;overflow:hidden;min-width:0}
.workflow-canvas-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;padding:11px 13px;border-bottom:1px solid #eaf0f6}
.workflow-canvas-actions{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.workflow-canvas-actions .btn{padding:7px 10px;font-size:10px}
.canvas-icon-btn{min-width:29px;height:29px;border:1px solid #dfe7f1;border-radius:7px;background:#fff;color:#516581;font-size:14px;display:inline-grid;place-items:center}
.canvas-icon-btn:hover{background:#f0f4ff}.canvas-icon-btn.danger{color:#a7474e;border-color:#f1d7d9}
.workflow-readonly-label{font-size:10px;color:#8b99ac}
.workflow-canvas{height:540px;position:relative;outline:none;background:#f7f9fd}
.workflow-canvas:focus-visible{box-shadow:inset 0 0 0 2px #7592dd}
.workflow-canvas :deep(.vue-flow){background-color:#f8faff;background-image:radial-gradient(#d9e2f0 1px,transparent 1px);background-size:18px 18px}
.workflow-canvas :deep(.vue-flow__node){border:1px solid #d7e2f1;border-radius:10px;background:#fff;box-shadow:0 6px 18px #34445c12;color:#2a3e59;font-size:11px;font-weight:700;max-width:230px;min-width:150px;padding:12px 13px;white-space:normal;line-height:1.4}
.workflow-canvas :deep(.vue-flow__node.workflow-node-condition){border-color:#dfc9ef;background:#fffaff}
.workflow-canvas :deep(.vue-flow__node.selected){box-shadow:0 0 0 2px #7190e1,0 8px 22px #34445c18}
.workflow-canvas :deep(.vue-flow__handle){width:8px;height:8px;background:#5577cb;border:2px solid #fff}
.workflow-canvas :deep(.vue-flow__edge-text){font-size:10px;font-weight:800;fill:#506789}
.workflow-canvas :deep(.vue-flow__edge.selected .vue-flow__edge-path){stroke:#4c6fca;stroke-width:2.5}
.workflow-canvas-empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:7px;pointer-events:none;color:#8797ad;font-size:11px}
.workflow-canvas-empty strong{color:#536984;font-size:13px}
.workflow-canvas-footer{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:10px 13px;border-top:1px solid #eaf0f6;color:#8c9bb0;font-size:9px}
.workflow-validity{border-radius:10px;padding:3px 7px;font-size:9px;font-weight:800}
.workflow-validity.valid{background:#e8f8ef;color:#2f855b}.workflow-validity.invalid{background:#fff0f0;color:#b1434a}
.workflow-shortcuts{margin-left:auto}
.workflow-inspector{padding:0;min-width:0;overflow:hidden}
.workflow-inspector-heading{display:flex;justify-content:space-between;align-items:center;padding:16px;border-bottom:1px solid #edf1f6}
.workflow-inspector-heading strong{font-size:12px}.workflow-inspector-heading small{font-size:9px;color:#96a3b5}
.workflow-inspector-title{display:flex;align-items:center;gap:9px;padding:16px 14px 8px}
.workflow-inspector-title>div{display:flex;flex-direction:column;gap:3px;min-width:0;flex:1}
.workflow-inspector-title strong{font-size:11px}.workflow-inspector-title small{font-size:9px;color:#8c9bb0;overflow-wrap:anywhere}
.workflow-inspector-glyph{width:29px;height:29px;display:grid;place-items:center;border-radius:8px;background:#edf2ff;color:#4d70c9;font-size:16px;flex-shrink:0}
.workflow-inspector fieldset{border:0;margin:0;padding:0 14px 16px;min-width:0}
.workflow-inspector label{margin-top:13px}
.workflow-inspector textarea{font-family:ui-monospace,monospace;resize:vertical}
.workflow-number-fields{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.workflow-static-field{display:flex;flex-direction:column;gap:4px;padding:10px 0;border-bottom:1px solid #edf1f6;margin-top:9px}
.workflow-static-field span{font-size:9px;color:#91a0b2;text-transform:uppercase;letter-spacing:.7px}
.workflow-static-field strong{font-size:11px}.workflow-static-field small{font-size:9px;color:#96a3b5}
.workflow-static-field code{font-size:9px;overflow-wrap:anywhere}
.workflow-field-hint{font-size:9px;color:#8998ad;line-height:1.5;margin:10px 0 0}
.workflow-apply-node{width:100%;margin-top:14px;padding:9px}
.workflow-inspector-empty{padding:40px 17px;text-align:center;display:flex;flex-direction:column;align-items:center;gap:8px}
.workflow-inspector-empty strong{font-size:11px}.workflow-inspector-empty p{font-size:10px;color:#8998ad}
.workflow-inspector-empty .workflow-inspector-glyph{width:44px;height:44px;font-size:22px;margin-bottom:4px}
.workflow-conflict-panel{padding:18px 20px;border-color:#f0d9b9;background:#fffdf8}
.workflow-conflict-heading{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.workflow-conflict-heading strong{font-size:12px}
.workflow-conflict-panel>p{font-size:11px;color:#806744;margin:9px 0}
.workflow-conflict-meta{font-size:10px;color:#8d7859;margin-bottom:10px}
.workflow-conflict-actions{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}
.workflow-conflict-diff{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.workflow-conflict-diff>div{min-width:0}.workflow-conflict-diff strong{font-size:10px}
.workflow-conflict-diff pre{max-height:240px;overflow:auto;background:#f8f7f2;border:1px solid #eee6d8;border-radius:8px;padding:10px;font-size:9px;white-space:pre-wrap;overflow-wrap:anywhere}
.workflow-validation{padding:15px 18px}
.workflow-validation-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.workflow-validation-heading>div{display:flex;flex-direction:column;gap:4px}
.workflow-validation-heading strong{font-size:12px}.workflow-validation-heading small{font-size:10px;color:#92a0b2}
.workflow-validation-list{margin:12px 0 0;padding-left:18px;color:#a64148;font-size:10px}
.workflow-validation-list li{margin:4px 0}
.workflow-validation-ok{font-size:10px;color:#4a8a6c;margin:12px 0 0}
@media(max-width:1180px){.workflow-editor-surface{grid-template-columns:minmax(0,1fr) 250px}.workflow-workspace{grid-template-columns:205px minmax(0,1fr)}}
@media(max-width:960px){.workflow-workspace{grid-template-columns:1fr}.workflow-library{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr))}.workflow-library-heading,.workflow-library-footer{grid-column:1/-1}.workflow-library-item{border-right:1px solid #f0f3f7}.workflow-editor-surface{grid-template-columns:1fr}.workflow-inspector{order:2}.workflow-canvas{height:460px}}
@media(max-width:620px){.workflow-heading-actions{width:100%;align-items:stretch}.workflow-project-picker{flex:1}.workflow-project-picker select{width:100%;min-width:0}.workflow-toolbar{align-items:flex-start}.workflow-toolbar-actions{width:100%}.workflow-canvas{height:390px}.workflow-shortcuts{width:100%;margin-left:0}.workflow-conflict-diff{grid-template-columns:1fr}.workflow-run-panel{align-items:stretch}.workflow-run-panel .btn{width:100%}}
</style>
