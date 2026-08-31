import http from './http'

export type GraphSizeValue = 'small' | 'medium' | 'large'

export interface GraphSizeOption {
  value: GraphSizeValue
  label: string
  limit: number
  description: string
}

export interface GraphOptions {
  techStacks: string[]
  levels: string[]
  sizes: GraphSizeOption[]
  evidenceOptions: number[]
  palette: string[]
  source: string
  snapshotVersion?: number
  generatedAt?: string
  stale?: boolean
  warning?: string
}

export interface GraphNode {
  id: string
  name: string
  type: 'ROLE' | 'SKILL'
  stack: string
  meta: string
  sampleCount?: number
  supportWeight?: number
  requiredWeight?: number
  bonusWeight?: number
  evidenceCount?: number
  roleCount?: number
  confidence?: number
  importance?: number
}

export interface GraphLink {
  source: string
  target: string
  type: 'REQUIRED' | 'BONUS' | 'MENTIONED'
  relationLabel: string
  weight: number
  supportWeight: number
  requiredWeight: number
  bonusWeight: number
  requiredRatio: number
  bonusRatio: number
  confidence: number
  evidenceCount: number
  techStack: string
  level: string
}

export interface GraphSummary {
  roleCount: number
  skillCount: number
  nodeCount: number
  linkCount: number
  requiredCount: number
  bonusCount: number
  mentionedCount: number
}

export interface GraphPayload {
  nodes: GraphNode[]
  links: GraphLink[]
  summary: GraphSummary
  stacks: Array<{
    name: string
    value: number
  }>
  filters: {
    techStack: string
    level: string
    limit: number
    minEvidence: number
  }
  palette: string[]
  source: string
  snapshotVersion?: number
  generatedAt?: string
  stale?: boolean
  warning?: string
}

export const graphApi = {
  options: (refresh = false) =>
    http.get('/graph/options', {
      params: { refresh },
      timeout: 300000
    }) as Promise<GraphOptions>,

  panorama: (
    techStack = '',
    level = '',
    limit = 650,
    minEvidence = 1,
    refresh = false
  ) =>
    http.get('/graph/panorama', {
      params: {
        techStack: techStack || undefined,
        level: level || undefined,
        limit,
        minEvidence,
        refresh
      },
      timeout: 600000
    }) as Promise<GraphPayload>,

  roles: () =>
    http.get('/graph/roles', {
      timeout: 120000
    }),

  ask: (question: string) =>
    http.post('/graph/ask', { question }, { timeout: 120000 }) as Promise<{
      answer: string
      evidence: Array<{ role: string; stack: string; skills: string[] }>
    }>
}
