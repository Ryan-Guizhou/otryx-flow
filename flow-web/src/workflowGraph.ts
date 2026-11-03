import type { WorkflowEdge, WorkflowGraphV1, WorkflowNode, WorkflowScalar } from './api.ts'

const MAX_GRAPH_BYTES = 48 * 1024
const MAX_NODE_PARAMS_BYTES = 16 * 1024
const MAX_NODES = 100
const MAX_EDGES = 500
const MAX_DEPTH = 32
const MAX_CONDITION_DEPTH = 6
const MAX_LIST_VALUES = 20
const MAX_IDENTIFIER_LENGTH = 128
const TRUSTED_HANDLERS = new Set(['safe.echo', 'safe.fail'])
const JSON_PATH = /^\$\.[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*){0,7}$/
const TASK_FIELDS = new Set(['id', 'type', 'label', 'handlerKey', 'parameters', 'timeoutSeconds', 'maxAttempts'])
const CONDITION_NODE_FIELDS = new Set(['id', 'type', 'label', 'condition'])
const EDGE_FIELDS = new Set(['id', 'source', 'target', 'branch'])
const GRAPH_FIELDS = new Set(['schemaVersion', 'nodes', 'edges'])
const UI_NODE_FIELDS = ['position', 'data', 'selected', 'dragging', 'resizing', 'computedPosition', 'dimensions', 'sourcePosition', 'targetPosition', 'extent', 'expandParent', 'parentNode', 'zIndex', 'draggable', 'connectable', 'focusable', 'selectable']
const UI_EDGE_FIELDS = ['data', 'selected', 'animated', 'markerEnd', 'markerStart', 'style', 'label', 'type', 'sourceHandle', 'targetHandle', 'updatable', 'focusable', 'selectable', 'zIndex', 'interactionWidth']

type JsonRecord = Record<string, unknown>
export interface WorkflowPosition { x: number; y: number }
export interface GraphIssue {
  key: string
  location?: string
  field?: string
  op?: string
  index?: number
  id?: string
  source?: string
  target?: string
  branch?: string
}

function issue(key: string, params: Omit<GraphIssue, 'key'> = {}): GraphIssue {
  return { key, ...params }
}

function dedupe(errors: GraphIssue[]): GraphIssue[] {
  const seen = new Set<string>()
  const unique: GraphIssue[] = []
  for (const item of errors) {
    const id = JSON.stringify(item)
    if (seen.has(id)) continue
    seen.add(id)
    unique.push(item)
    if (unique.length === 30) break
  }
  return unique
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

function validIdentifier(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
    && value.length <= MAX_IDENTIFIER_LENGTH && !value.includes('\u0000')
}

function onlyFields(value: JsonRecord, allowed: Set<string>, location: string, errors: GraphIssue[]): void {
  for (const field of Object.keys(value)) {
    if (!allowed.has(field)) errors.push(issue('unsupportedField', { location, field }))
  }
}

function scalar(value: unknown): value is WorkflowScalar {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') {
    return byteLength(JSON.stringify(value)) <= 256
  }
  return typeof value === 'number' && Number.isFinite(value)
    && byteLength(JSON.stringify(value)) <= 256
}

function validateCondition(value: unknown, depth: number, location: string, errors: GraphIssue[]): void {
  if (depth > MAX_CONDITION_DEPTH) {
    errors.push(issue('conditionDepth', { location }))
    return
  }
  if (!isRecord(value) || typeof value.op !== 'string') {
    errors.push(issue('conditionObject', { location }))
    return
  }
  const op = value.op
  if (op === 'and' || op === 'or') {
    onlyFields(value, new Set(['op', 'conditions']), location, errors)
    if (!Array.isArray(value.conditions) || value.conditions.length < 2 || value.conditions.length > 10) {
      errors.push(issue('conditionChildren', { location, op }))
      return
    }
    value.conditions.forEach((child, index) => validateCondition(child, depth + 1,
      location + '.conditions[' + index + ']', errors))
    return
  }
  if (!['equals', 'notEquals', 'exists', 'in'].includes(op)) {
    errors.push(issue('unsupportedOperator', { location }))
    return
  }
  if (!validIdentifier(value.path) || !JSON_PATH.test(value.path)) {
    errors.push(issue('conditionPath', { location }))
  }
  if (op === 'exists') {
    onlyFields(value, new Set(['op', 'path']), location, errors)
    return
  }
  if (op === 'in') {
    onlyFields(value, new Set(['op', 'path', 'values']), location, errors)
    if (!Array.isArray(value.values) || value.values.length < 1 || value.values.length > MAX_LIST_VALUES) {
      errors.push(issue('inValues', { location }))
      return
    }
    value.values.forEach((item, index) => {
      if (!scalar(item)) errors.push(issue('scalarItem', { location, index }))
    })
    return
  }
  onlyFields(value, new Set(['op', 'path', 'value']), location, errors)
  if (!Object.prototype.hasOwnProperty.call(value, 'value') || !scalar(value.value)) {
    errors.push(issue('scalarValue', { location }))
  }
}

function sanitizeNode(node: WorkflowNode): WorkflowNode {
  if (!isRecord(node)) return node
  const raw = node as unknown as JsonRecord
  const output: JsonRecord = { id: raw.id, type: raw.type }
  if (typeof raw.label === 'string' && raw.label.trim()) output.label = raw.label.trim()
  if (raw.type === 'TASK') {
    for (const field of ['handlerKey', 'parameters', 'timeoutSeconds', 'maxAttempts']) {
      if (Object.prototype.hasOwnProperty.call(raw, field)) output[field] = raw[field]
    }
    return output as unknown as WorkflowNode
  }
  if (raw.type === 'CONDITION') {
    if (Object.prototype.hasOwnProperty.call(raw, 'condition')) output.condition = raw.condition
    return output as unknown as WorkflowNode
  }
  const unknownNode: JsonRecord = { ...raw }
  for (const field of UI_NODE_FIELDS) delete unknownNode[field]
  return unknownNode as unknown as WorkflowNode
}

function sanitizeEdge(edge: WorkflowEdge): WorkflowEdge {
  if (!isRecord(edge)) return edge
  const raw = edge as unknown as JsonRecord
  const output: JsonRecord = {}
  for (const field of ['id', 'source', 'target', 'branch']) {
    if (Object.prototype.hasOwnProperty.call(raw, field)) output[field] = raw[field]
  }
  for (const field of Object.keys(raw)) {
    if (!UI_EDGE_FIELDS.includes(field) && !EDGE_FIELDS.has(field)) output[field] = raw[field]
  }
  return output as unknown as WorkflowEdge
}

/** Removes Vue Flow layout/runtime properties and emits stable backend-only JSON. */
export function serializeWorkflowGraph(graph: WorkflowGraphV1): string {
  const nodes = (Array.isArray(graph.nodes) ? graph.nodes : [])
    .map(node => sanitizeNode(node))
    .sort((left, right) => String(left.id) < String(right.id) ? -1 : String(left.id) > String(right.id) ? 1 : 0)
  const edges = (Array.isArray(graph.edges) ? graph.edges : [])
    .map(edge => sanitizeEdge(edge))
    .sort((left, right) => String(left.id) < String(right.id) ? -1 : String(left.id) > String(right.id) ? 1 : 0)
  return JSON.stringify({ schemaVersion: graph.schemaVersion, nodes, edges })
}

/** Client-side fast feedback; the metadata service remains the authoritative validator. */
export function validateWorkflowGraph(input: unknown): GraphIssue[] {
  const errors: GraphIssue[] = []
  if (!isRecord(input)) return [issue('notObject')]
  onlyFields(input, GRAPH_FIELDS, 'Graph', errors)
  if (input.schemaVersion !== 1) errors.push(issue('schemaVersion'))
  if (!Array.isArray(input.nodes) || input.nodes.length < 1 || input.nodes.length > MAX_NODES) {
    errors.push(issue('nodeCount'))
  }
  if (!Array.isArray(input.edges) || input.edges.length > MAX_EDGES) {
    errors.push(issue('edgeCount'))
  }
  if (!Array.isArray(input.nodes) || !Array.isArray(input.edges)) return dedupe(errors)
  let encoded = ''
  try { encoded = JSON.stringify(input) } catch { errors.push(issue('notJson')) }
  if (encoded && byteLength(encoded) > MAX_GRAPH_BYTES) errors.push(issue('tooLarge'))

  const nodes = input.nodes
  const edges = input.edges
  const nodeIds = new Set<string>()
  const nodeTypes = new Map<string, string>()
  const conditionBranches = new Map<string, Set<string>>()

  nodes.forEach((value, index) => {
    const location = 'Node ' + (index + 1)
    if (!isRecord(value)) {
      errors.push(issue('nodeObject', { location }))
      return
    }
    if (!validIdentifier(value.id)) {
      errors.push(issue('nodeId', { location }))
    } else if (nodeIds.has(value.id)) {
      errors.push(issue('duplicateNodeId', { id: value.id }))
    } else {
      nodeIds.add(value.id)
    }
    if (value.type !== 'TASK' && value.type !== 'CONDITION') {
      errors.push(issue('nodeType', { location }))
      return
    }
    onlyFields(value, value.type === 'TASK' ? TASK_FIELDS : CONDITION_NODE_FIELDS, location, errors)
    if (Object.prototype.hasOwnProperty.call(value, 'label')
        && (typeof value.label !== 'string' || !value.label.trim() || value.label.length > 100)) {
      errors.push(issue('nodeLabel', { location }))
    }
    if (validIdentifier(value.id)) nodeTypes.set(value.id, value.type)
    if (value.type === 'TASK') {
      if (typeof value.handlerKey !== 'string' || !TRUSTED_HANDLERS.has(value.handlerKey)) {
        errors.push(issue('handler', { location }))
      }
      if (!isRecord(value.parameters)) {
        errors.push(issue('parametersObject', { location }))
      } else {
        let params = ''
        try { params = JSON.stringify(value.parameters) } catch { params = '' }
        if (!params || byteLength(params) > MAX_NODE_PARAMS_BYTES) {
          errors.push(issue('parametersSize', { location }))
        }
      }
      if (!Number.isInteger(value.timeoutSeconds) || Number(value.timeoutSeconds) < 1
          || Number(value.timeoutSeconds) > 86400) {
        errors.push(issue('timeout', { location }))
      }
      if (!Number.isInteger(value.maxAttempts) || Number(value.maxAttempts) < 1
          || Number(value.maxAttempts) > 10) {
        errors.push(issue('maxAttempts', { location }))
      }
    } else {
      conditionBranches.set(String(value.id), new Set<string>())
      validateCondition(value.condition, 0, location + ' condition', errors)
    }
  })

  const edgeIds = new Set<string>()
  const directedPairs = new Set<string>()
  const indegree = new Map<string, number>()
  const adjacency = new Map<string, string[]>()
  nodeIds.forEach(id => { indegree.set(id, 0); adjacency.set(id, []) })

  edges.forEach((value, index) => {
    const location = 'Edge ' + (index + 1)
    if (!isRecord(value)) {
      errors.push(issue('edgeObject', { location }))
      return
    }
    onlyFields(value, EDGE_FIELDS, location, errors)
    for (const field of ['id', 'source', 'target']) {
      if (!validIdentifier(value[field])) errors.push(issue('edgeField', { location, field }))
    }
    if (!['ALWAYS', 'TRUE', 'FALSE'].includes(String(value.branch))) {
      errors.push(issue('edgeBranch', { location }))
    }
    if (!validIdentifier(value.id) || !validIdentifier(value.source) || !validIdentifier(value.target)) return
    if (edgeIds.has(value.id)) errors.push(issue('duplicateEdgeId', { id: value.id }))
    edgeIds.add(value.id)
    if (!nodeIds.has(value.source) || !nodeIds.has(value.target)) {
      errors.push(issue('missingNode', { location }))
      return
    }
    if (value.source === value.target) errors.push(issue('selfEdge', { location }))
    const pair = value.source + '\u0000' + value.target
    if (directedPairs.has(pair)) errors.push(issue('duplicateDirected', { source: value.source, target: value.target }))
    directedPairs.add(pair)
    const sourceType = nodeTypes.get(value.source)
    if (sourceType === 'TASK' && value.branch !== 'ALWAYS') {
      errors.push(issue('taskAlways', { location }))
    }
    if (sourceType === 'CONDITION') {
      if (value.branch !== 'TRUE' && value.branch !== 'FALSE') {
        errors.push(issue('conditionBranch', { location }))
      } else {
        const branches = conditionBranches.get(value.source)
        if (branches?.has(value.branch)) errors.push(issue('duplicateBranch', { id: value.source, branch: value.branch }))
        branches?.add(value.branch)
      }
    }
    if (value.source !== value.target && adjacency.has(value.source) && indegree.has(value.target)) {
      adjacency.get(value.source)?.push(value.target)
      indegree.set(value.target, (indegree.get(value.target) ?? 0) + 1)
    }
  })

  conditionBranches.forEach((branches, id) => {
    if (branches.size !== 2 || !branches.has('TRUE') || !branches.has('FALSE')) {
      errors.push(issue('conditionEdges', { id }))
    }
  })

  const ready: string[] = []
  const depth = new Map<string, number>()
  indegree.forEach((degree, id) => {
    if (degree === 0) { ready.push(id); depth.set(id, 1) }
  })
  if (nodeIds.size > 0 && ready.length === 0) errors.push(issue('noEntry'))
  let processed = 0
  let maxDepth = 0
  while (ready.length) {
    const current = ready.shift() as string
    processed += 1
    const currentDepth = depth.get(current) ?? 1
    maxDepth = Math.max(maxDepth, currentDepth)
    for (const target of adjacency.get(current) ?? []) {
      depth.set(target, Math.max(depth.get(target) ?? 1, currentDepth + 1))
      const nextDegree = (indegree.get(target) ?? 0) - 1
      indegree.set(target, nextDegree)
      if (nextDegree === 0) ready.push(target)
    }
  }
  if (processed !== nodeIds.size) errors.push(issue('cycle'))
  if (maxDepth > MAX_DEPTH) errors.push(issue('depth'))
  return dedupe(errors)
}

/** Stable starting graph: one valid task node; canvas coordinates are never part of this object. */
export function defaultWorkflowGraph(): WorkflowGraphV1 {
  return {
    schemaVersion: 1,
    nodes: [{
      id: 'A',
      type: 'TASK',
      label: 'Start',
      handlerKey: 'safe.echo',
      parameters: {},
      timeoutSeconds: 30,
      maxAttempts: 2,
    }],
    edges: [],
  }
}

/** Compute a predictable initial canvas layout without mutating the saved graph. */
export function computeDefaultLayout(graph: WorkflowGraphV1): Record<string, WorkflowPosition> {
  const nodes = Array.isArray(graph.nodes) ? graph.nodes : []
  const edges = Array.isArray(graph.edges) ? graph.edges : []
  const ids = nodes.map((node, index) => validIdentifier(node?.id) ? node.id : 'invalid-node-' + index)
  const idSet = new Set(ids)
  const indegree = new Map<string, number>()
  const adjacency = new Map<string, string[]>()
  ids.forEach(id => { indegree.set(id, 0); adjacency.set(id, []) })
  edges.forEach(edge => {
    if (edge && idSet.has(edge.source) && idSet.has(edge.target) && edge.source !== edge.target) {
      adjacency.get(edge.source)?.push(edge.target)
      indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
    }
  })
  const queue = ids.filter(id => (indegree.get(id) ?? 0) === 0)
  const depth = new Map<string, number>()
  queue.forEach(id => depth.set(id, 1))
  while (queue.length) {
    const current = queue.shift() as string
    for (const target of adjacency.get(current) ?? []) {
      depth.set(target, Math.max(depth.get(target) ?? 1, (depth.get(current) ?? 1) + 1))
      const degree = (indegree.get(target) ?? 0) - 1
      indegree.set(target, degree)
      if (degree === 0) queue.push(target)
    }
  }
  const rowsByDepth = new Map<number, number>()
  const positions: Record<string, WorkflowPosition> = {}
  nodes.forEach((node, index) => {
    const id = ids[index] as string
    const column = depth.get(id) ?? 1
    const row = rowsByDepth.get(column) ?? 0
    rowsByDepth.set(column, row + 1)
    positions[id] = { x: 70 + (column - 1) * 260, y: 55 + row * 145 }
  })
  return positions
}
