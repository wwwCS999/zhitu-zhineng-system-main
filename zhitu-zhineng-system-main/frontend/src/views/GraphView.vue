<script setup lang="ts">
import {
  computed,
  onMounted,
  ref,
  watch
} from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import EChart from '@/components/EChart.vue'
import {
  graphApi,
  type GraphLink,
  type GraphNode,
  type GraphOptions,
  type GraphPayload,
  type GraphSizeValue
} from '@/api/graph'

const chartRef = ref<any>(null)
const loading = ref(false)
const error = ref('')

const graph = ref<GraphPayload>({
  nodes: [],
  links: [],
  summary: {
    roleCount: 0,
    skillCount: 0,
    nodeCount: 0,
    linkCount: 0,
    requiredCount: 0,
    bonusCount: 0,
    mentionedCount: 0
  },
  stacks: [],
  filters: {
    techStack: '',
    level: '',
    limit: 650,
    minEvidence: 1
  },
  palette: [],
  source: ''
})

const meta = ref<GraphOptions>({
  techStacks: [],
  levels: [],
  sizes: [
    {
      value: 'small',
      label: '小图谱',
      limit: 240,
      description: '适合快速查看和答辩演示'
    },
    {
      value: 'medium',
      label: '中图谱',
      limit: 650,
      description: '推荐日常分析'
    },
    {
      value: 'large',
      label: '大图谱',
      limit: 1200,
      description: '适合全景能力探索'
    }
  ],
  evidenceOptions: [1, 2, 3, 5, 10, 20, 50],
  palette: [
    '#2563EB',
    '#0F766E',
    '#0891B2',
    '#7C3AED',
    '#F59E0B',
    '#DB2777',
    '#16A34A',
    '#475569'
  ],
  source: ''
})

const sizePreset = ref<GraphSizeValue>('medium')

// 基于图谱的 RAG 问答
const graphQuestion = ref('')
const graphAnswer = ref('')
const graphEvidence = ref<Array<{ role: string; stack: string; skills: string[] }>>([])
const graphAsking = ref(false)

async function askGraph() {
  const q = graphQuestion.value.trim()
  if (!q) return
  graphAsking.value = true
  graphAnswer.value = ''
  graphEvidence.value = []
  try {
    const result = await graphApi.ask(q)
    graphAnswer.value = result?.answer || ''
    graphEvidence.value = result?.evidence || []
  } catch (err: any) {
    ElMessage.error(err?.message || '图谱问答失败')
  } finally {
    graphAsking.value = false
  }
}
const techStack = ref('')
const level = ref('')
const minEvidence = ref(3)

type CenterType = 'ALL' | 'ROLE' | 'SKILL'

const centerType = ref<CenterType>('ALL')
const centerId = ref('')
const depth = ref(1)
const selectedNodeId = ref('')
const selectedEdge = ref<GraphLink | null>(null)

const fallbackPalette = [
  '#2563EB',
  '#0F766E',
  '#0891B2',
  '#7C3AED',
  '#F59E0B',
  '#DB2777',
  '#16A34A',
  '#475569'
]

const palette = computed(() => {
  return fallbackPalette
})

function hashText(text = '') {
  let hash = 0

  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 31 + text.charCodeAt(index)) >>> 0
  }

  return hash
}

function stackColor(value = '') {
  const colors = palette.value

  if (!colors.length) {
    return '#51999F'
  }

  return colors[hashText(value) % colors.length]
}

function hexToRgba(hex = '#2563EB', alpha = 1) {
  const normalized = hex.replace('#', '').trim()
  const expanded = normalized.length === 3
    ? normalized.split('').map(char => `${char}${char}`).join('')
    : normalized

  if (!/^[0-9a-f]{6}$/i.test(expanded)) {
    return `rgba(37, 99, 235, ${alpha})`
  }

  const value = Number.parseInt(expanded, 16)
  const red = (value >> 16) & 255
  const green = (value >> 8) & 255
  const blue = value & 255

  return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

const selectedSize = computed(() =>
  meta.value.sizes.find(item => item.value === sizePreset.value)
)

const graphLimit = computed(() => selectedSize.value?.limit ?? 650)

/**
 * 舒展布局：图谱规模越大，画布越高。
 * 这样力导向布局有足够空间把岗位簇和技能簇真正摊开。
 */
const chartHeight = computed(() => {
  const nodeCount = visibleGraph.value.nodes.length

  if (sizePreset.value === 'small') {
    return nodeCount > 120 ? '640px' : '600px'
  }

  if (sizePreset.value === 'large') {
    return nodeCount > 360 ? '820px' : '760px'
  }

  return nodeCount > 220 ? '720px' : '680px'
})

async function loadOptions(refresh = false) {
  try {
    meta.value = await graphApi.options(refresh)
  } catch (err) {
    console.warn('图谱筛选项加载失败，将使用本地默认配置：', err)
  }
}

async function loadGraph(refresh = false) {
  loading.value = true
  error.value = ''

  try {
    const response = await graphApi.panorama(
      techStack.value,
      level.value,
      graphLimit.value,
      minEvidence.value,
      refresh
    )

    graph.value = response

    if (response.warning) {
      ElMessage.warning(response.warning)
    }

    selectedNodeId.value = ''
    selectedEdge.value = null
    normalizeCenter()

    ElMessage.success(
      `图谱构建完成：${response.summary?.nodeCount || 0} 个节点，${response.summary?.linkCount || 0} 条关系`
    )
  } catch (err: any) {
    error.value = err?.message || '能力图谱构建失败'
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

const centerCandidates = computed<GraphNode[]>(() => {
  if (centerType.value === 'ALL') {
    return []
  }

  return [...(graph.value.nodes || [])]
    .filter(node => node.type === centerType.value)
    .sort((a, b) => {
      if (centerType.value === 'ROLE') {
        return Number(b.sampleCount || 0) - Number(a.sampleCount || 0)
      }

      return Number(b.evidenceCount || 0) - Number(a.evidenceCount || 0)
    })
})

function normalizeCenter() {
  if (centerType.value === 'ALL') {
    centerId.value = ''
    return
  }

  const exists = centerCandidates.value.some(node => node.id === centerId.value)

  if (!exists) {
    centerId.value = centerCandidates.value[0]?.id || ''
  }
}

watch(centerType, () => {
  const currentCenterId = centerId.value
  const canKeepCurrentCenter = centerCandidates.value.some(node => node.id === currentCenterId)

  selectedEdge.value = null

  if (canKeepCurrentCenter) {
    selectedNodeId.value = currentCenterId
    return
  }

  centerId.value = ''
  selectedNodeId.value = ''
  normalizeCenter()
})

watch(
  () => graph.value.nodes,
  () => normalizeCenter()
)

const visibleGraph = computed(() => {
  const allNodes = graph.value.nodes || []
  const allLinks = graph.value.links || []

  if (centerType.value === 'ALL' || !centerId.value) {
    return {
      nodes: allNodes,
      links: allLinks
    }
  }

  const adjacency = new Map<string, Set<string>>()

  function connect(source: string, target: string) {
    if (!adjacency.has(source)) {
      adjacency.set(source, new Set<string>())
    }

    adjacency.get(source)!.add(target)
  }

  for (const link of allLinks) {
    connect(link.source, link.target)
    connect(link.target, link.source)
  }

  const visited = new Set<string>([centerId.value])
  let frontier = new Set<string>([centerId.value])
  const maxDepth = Math.max(1, Math.min(depth.value, 3))

  for (let hop = 0; hop < maxDepth; hop += 1) {
    const next = new Set<string>()

    for (const current of frontier) {
      const neighbors = adjacency.get(current)

      if (!neighbors) {
        continue
      }

      for (const neighbor of neighbors) {
        if (!visited.has(neighbor)) {
          visited.add(neighbor)
          next.add(neighbor)
        }
      }
    }

    frontier = next

    if (!frontier.size) {
      break
    }
  }

  return {
    nodes: allNodes.filter(node => visited.has(node.id)),
    links: allLinks.filter(
      link => visited.has(link.source) && visited.has(link.target)
    )
  }
})

const visibleSummary = computed(() => {
  const nodes = visibleGraph.value.nodes
  const links = visibleGraph.value.links

  return {
    roles: nodes.filter(node => node.type === 'ROLE').length,
    skills: nodes.filter(node => node.type === 'SKILL').length,
    nodes: nodes.length,
    links: links.length
  }
})

const selectedNode = computed<GraphNode | null>(() => {
  if (!selectedNodeId.value) {
    return null
  }

  return graph.value.nodes.find(node => node.id === selectedNodeId.value) || null
})

const nodeMap = computed(() => {
  const map = new Map<string, GraphNode>()

  for (const node of graph.value.nodes) {
    map.set(node.id, node)
  }

  return map
})

const selectedNeighborhood = computed(() => {
  const set = new Set<string>()

  if (!selectedNodeId.value) {
    return set
  }

  set.add(selectedNodeId.value)

  for (const link of visibleGraph.value.links) {
    if (link.source === selectedNodeId.value) {
      set.add(link.target)
    }

    if (link.target === selectedNodeId.value) {
      set.add(link.source)
    }
  }

  return set
})

interface NeighborInfo {
  node: GraphNode
  link: GraphLink
}

const selectedNeighbors = computed<NeighborInfo[]>(() => {
  const id = selectedNodeId.value

  if (!id) {
    return []
  }

  const result: NeighborInfo[] = []

  for (const link of graph.value.links) {
    let otherId = ''

    if (link.source === id) {
      otherId = link.target
    } else if (link.target === id) {
      otherId = link.source
    }

    if (!otherId) {
      continue
    }

    const node = nodeMap.value.get(otherId)

    if (!node) {
      continue
    }

    result.push({ node, link })
  }

  return result
    .sort(
      (a, b) =>
        Number(b.link.evidenceCount || 0) -
        Number(a.link.evidenceCount || 0)
    )
    .slice(0, 16)
})

function nodeSymbolSize(node: GraphNode) {
  if (node.type === 'ROLE') {
    const samples = Number(node.sampleCount || 1)

    return Math.max(36, Math.min(58, 34 + Math.log1p(samples) * 3.1))
  }

  const evidence = Number(node.evidenceCount || 1)

  return Math.max(13, Math.min(28, 13 + Math.log1p(evidence) * 1.7))
}

function linkColor(link: GraphLink) {
  if (link.type === 'REQUIRED') {
    return '#2563EB'
  }

  if (link.type === 'BONUS') {
    return '#F59E0B'
  }

  return '#94A3B8'
}

function linkLabel(type: string) {
  if (type === 'REQUIRED') {
    return '必备技能'
  }

  if (type === 'BONUS') {
    return '加分技能'
  }

  return '相关技能'
}

/**
 * 岗位标签过多是“拥挤感”的主要来源之一。
 * 默认只常驻显示证据最强的一部分岗位；其余节点仍完整保留，
 * 鼠标悬停、点击或成为邻居时会自动显示标签。
 */
const persistentRoleLabelIds = computed(() => {
  const limit = sizePreset.value === 'small'
    ? 14
    : sizePreset.value === 'large'
      ? 28
      : 20

  return new Set(
    [...visibleGraph.value.nodes]
      .filter(node => node.type === 'ROLE')
      .sort(
        (a, b) =>
          Number(b.sampleCount || 0) - Number(a.sampleCount || 0)
      )
      .slice(0, limit)
      .map(node => node.id)
  )
})

const chartOption = computed(() => {
  const hasSelected = Boolean(selectedNodeId.value)
  const neighborhood = selectedNeighborhood.value
  const visibleNodes = visibleGraph.value.nodes
  const visibleLinks = visibleGraph.value.links
  const large = sizePreset.value === 'large'
  const small = sizePreset.value === 'small'
  const nodeCount = visibleNodes.length

  /*
   * 关键：把斥力与边长整体调高，同时降低向中心收缩的 gravity。
   * 节点越多，布局参数会自动增加留白，而不是全部挤在画布中心。
   */
  const densityBoost = Math.min(1.35, 1 + nodeCount / 900)

  const forceRepulsion = small
    ? Math.round(520 * densityBoost)
    : large
      ? Math.round(360 * densityBoost)
      : Math.round(430 * densityBoost)

  const forceEdgeLength = large
    ? [120, 205]
    : small
      ? [165, 260]
      : [140, 230]

  const initialZoom = large
    ? 0.58
    : small
      ? 0.82
      : 0.68

  return {
    animationDuration: 420,
    animationDurationUpdate: 220,
    animationEasing: 'cubicOut',
    animationEasingUpdate: 'cubicOut',
    backgroundColor: '#f8fafc',
    tooltip: {
      confine: true,
      backgroundColor: 'rgba(15, 23, 42, .96)',
      borderColor: 'rgba(148, 163, 184, .28)',
      borderWidth: 1,
      padding: [10, 12],
      extraCssText: 'box-shadow:0 12px 24px rgba(15,23,42,.16);border-radius:8px;',
      textStyle: {
        color: '#F8FAFC',
        fontSize: 12
      },
      formatter: (params: any) => {
        if (params.dataType === 'edge') {
          const edge = params.data as GraphLink
          const source = nodeMap.value.get(edge.source)
          const target = nodeMap.value.get(edge.target)

          return [
            `<b>${source?.name || ''} → ${target?.name || ''}</b>`,
            `关系：${linkLabel(edge.type)}`,
            `证据：${fmt(edge.evidenceCount)} 条`,
            `置信度：${pct(edge.confidence)}`,
            `证据强度：${Number(edge.supportWeight || 0).toFixed(2)}`
          ].join('<br/>')
        }

        const node = params.data as GraphNode

        if (node.type === 'ROLE') {
          return [
            `<b>${node.name}</b>`,
            '类型：岗位',
            `技术栈：${node.stack || '未分类'}`,
            `级别：${node.meta || '未标注'}`,
            `JD 证据：${fmt(node.sampleCount)} 条`,
            `可信度：${pct(node.confidence)}`
          ].join('<br/>')
        }

        return [
          `<b>${node.name}</b>`,
          '类型：技能点',
          `技术栈：${node.stack || '未分类'}`,
          `类别：${node.meta || '未分类'}`,
          `关联岗位：${fmt(node.roleCount)}`,
          `证据：${fmt(node.evidenceCount)} 条`,
          `可信度：${pct(node.confidence)}`
        ].join('<br/>')
      }
    },
    series: [
      {
        id: 'capability-network',
        name: '岗位能力图谱',
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        zoom: initialZoom,
        center: ['50%', '50%'],
        left: '4%',
        right: '4%',
        top: '4%',
        bottom: '4%',
        scaleLimit: {
          min: 0.25,
          max: 4.5
        },
        focusNodeAdjacency: true,
        selectedMode: 'single',
        cursor: 'pointer',
        edgeSymbol: ['none', 'none'],
        data: visibleNodes.map(node => {
          const isRole = node.type === 'ROLE'
          const selected = selectedNodeId.value === node.id
          const dimmed = hasSelected && !neighborhood.has(node.id)
          const color = stackColor(node.stack || node.meta || node.name)
          const showSkillLabel =
            small ||
            selected ||
            (hasSelected && neighborhood.has(node.id)) ||
            (centerType.value === 'SKILL' && node.id === centerId.value)

          const showRoleLabel =
            persistentRoleLabelIds.value.has(node.id) ||
            selected ||
            (hasSelected && neighborhood.has(node.id)) ||
            (centerType.value === 'ROLE' && node.id === centerId.value)

          return {
            ...node,
            value:
              node.type === 'ROLE'
                ? Number(node.sampleCount || 0)
                : Number(node.evidenceCount || 0),
            category: isRole ? 0 : 1,
            symbol: isRole ? 'roundRect' : 'circle',
            symbolSize: nodeSymbolSize(node),
            itemStyle: {
              color: dimmed ? hexToRgba(color, 0.16) : isRole ? hexToRgba(color, 0.92) : hexToRgba(color, 0.78),
              opacity: dimmed ? 0.18 : isRole ? 0.94 : 0.84,
              borderColor: selected
                ? '#0f172a'
                : isRole
                  ? '#ffffff'
                  : hexToRgba(color, 0.38),
              borderWidth: selected ? 3 : isRole ? 2 : 1.2,
              shadowBlur: selected ? 12 : isRole ? 5 : 0,
              shadowColor: dimmed ? 'transparent' : 'rgba(15, 23, 42, 0.16)'
            },
            label: {
              show: isRole ? showRoleLabel : showSkillLabel,
              position: isRole ? 'right' : 'bottom',
              distance: isRole ? 10 : 5,
              formatter: node.name,
              color: dimmed
                ? 'rgba(100,116,139,.25)'
                : isRole
                  ? '#0f172a'
                  : '#334155',
              backgroundColor: isRole && !dimmed ? 'rgba(255,255,255,.88)' : 'transparent',
              borderColor: isRole && !dimmed ? '#e2e8f0' : 'transparent',
              borderWidth: isRole && !dimmed ? 1 : 0,
              borderRadius: 6,
              padding: isRole && !dimmed ? [4, 7] : 0,
              fontSize: isRole ? 11 : 9,
              fontWeight: isRole ? 800 : 700
            },
            blur: {
              itemStyle: {
                opacity: 0.08
              },
              label: {
                show: false
              }
            },
            emphasis: {
              focus: 'adjacency',
              scale: true,
              itemStyle: {
                borderColor: '#0f172a',
                borderWidth: 3,
                shadowBlur: 14,
                shadowColor: hexToRgba(color, 0.26)
              },
              label: {
                show: true,
                color: '#0f172a',
                backgroundColor: 'rgba(255,255,255,.94)',
                borderColor: '#cbd5e1',
                borderWidth: 1,
                borderRadius: 6,
                padding: [4, 7],
                fontWeight: 900
              }
            }
          }
        }),
        links: visibleLinks.map(link => {
          const active =
            !hasSelected ||
            link.source === selectedNodeId.value ||
            link.target === selectedNodeId.value
          const baseColor = linkColor(link)
          const curveSeed = hashText(`${link.source}-${link.target}-${link.type}`) % 5

          return {
            ...link,
            lineStyle: {
              color: hexToRgba(baseColor, active ? 0.48 : 0.12),
              width: active
                ? Math.max(
                    1,
                    Math.min(3.6, 0.9 + Number(link.weight || 0) * 2.2)
                  )
                : 0.7,
              opacity: active
                ? 0.22 + Number(link.confidence || 0) * 0.38
                : 0.05,
              curveness: 0.06 + curveSeed * 0.018,
              shadowBlur: 0
            },
            label: {
              show: false
            },
            blur: {
              lineStyle: {
                opacity: 0.025,
                width: 0.5
              }
            },
            emphasis: {
              lineStyle: {
                color: hexToRgba(baseColor, 0.76),
                width: 3.5,
                opacity: 0.9
              },
              label: {
                show: true,
                formatter: linkLabel(link.type),
                color: '#0f172a',
                backgroundColor: 'rgba(255,255,255,.94)',
                borderColor: '#cbd5e1',
                borderWidth: 1,
                borderRadius: 6,
                padding: [4, 7],
                fontSize: 10,
                fontWeight: 900
              }
            }
          }
        }),
        categories: [
          { name: '岗位' },
          { name: '技能点' }
        ],
        force: {
          initLayout: 'circular',
          repulsion: forceRepulsion,
          gravity: large ? 0.012 : small ? 0.018 : 0.014,
          edgeLength: forceEdgeLength,
          friction: 0.55,
          layoutAnimation: true
        },
        labelLayout: {
          hideOverlap: true
        },
        emphasis: {
          focus: 'adjacency'
        }
      }
    ]
  }
})

function handleChartClick(params: any) {
  if (params.dataType === 'node') {
    selectedNodeId.value = params.data?.id ?? params.data?.name ?? ''
    selectedEdge.value = null
    return
  }

  if (params.dataType === 'edge') {
    selectedEdge.value = params.data as GraphLink
    selectedNodeId.value = ''
  }
}

function clearSelection() {
  selectedNodeId.value = ''
  selectedEdge.value = null
}

function selectNeighbor(node: GraphNode) {
  selectedNodeId.value = node.id
  selectedEdge.value = null
}

function useSelectedAsCenter() {
  const node = selectedNode.value

  if (!node) {
    return
  }

  centerType.value = node.type
  centerId.value = node.id
  selectedNodeId.value = node.id
  selectedEdge.value = null
  depth.value = 1
}

function resetView() {
  centerType.value = 'ALL'
  centerId.value = ''
  depth.value = 1
  selectedNodeId.value = ''
  selectedEdge.value = null
  chartRef.value?.restoreView?.()
}

/**
 * 重新运行当前舒展参数下的力导向布局。
 * 当用户拖拽较多节点后，可以一键重新平铺。
 */
function spreadGraph() {
  selectedNodeId.value = ''
  selectedEdge.value = null
  chartRef.value?.restoreView?.()
  ElMessage.success('已重新执行舒展布局')
}

function safeFileName(name: string) {
  return name.replace(/[\\/:*?"<>|]/g, '-')
}

const exportBaseName = computed(() => {
  const stackName = techStack.value || '全部技术栈'
  const currentLevel = level.value || '全部级别'

  return safeFileName(
    `职途智配-岗位能力图谱-${stackName}-${currentLevel}`
  )
})

async function exportPng() {
  try {
    await chartRef.value?.exportPng?.(
      `${exportBaseName.value}.png`,
      2
    )
    ElMessage.success('PNG 图谱已导出')
  } catch (err: any) {
    ElMessage.error(err?.message || 'PNG 导出失败')
  }
}

function fmt(value: unknown) {
  return new Intl.NumberFormat('zh-CN').format(Number(value || 0))
}

function pct(value: unknown) {
  return `${Math.round(Number(value || 0) * 100)}%`
}

function roleSkillLabel(node: GraphNode) {
  return node.type === 'ROLE' ? '岗位节点' : '技能节点'
}

function relationClass(type: string) {
  if (type === 'REQUIRED') {
    return 'required'
  }

  if (type === 'BONUS') {
    return 'bonus'
  }

  return 'mentioned'
}

function sourceName(edge: GraphLink) {
  return nodeMap.value.get(edge.source)?.name || edge.source
}

function targetName(edge: GraphLink) {
  return nodeMap.value.get(edge.target)?.name || edge.target
}

onMounted(async () => {
  await loadOptions()
  await loadGraph()
  loadEvolution()
})

// 能力演化（岗位技能演化）
const evolutionEvents = ref<any[]>([])
const evolutionFilter = ref('ALL')
const evolutionLoading = ref(false)
const selectedEvolutionId = ref<number | null>(null)
const evolutionShown = computed(() => {
  const rows = evolutionFilter.value === 'ALL'
    ? evolutionEvents.value
    : evolutionEvents.value.filter((item: any) => item.change_type === evolutionFilter.value)
  return [...rows].sort((a, b) => Math.abs(evolutionDelta(b)) - Math.abs(evolutionDelta(a)))
})
const evolutionCounts = computed(() => ({
  added: evolutionEvents.value.filter((item: any) => item.change_type === 'ADDED').length,
  weakened: evolutionEvents.value.filter((item: any) => item.change_type === 'WEAKENED').length,
  modified: evolutionEvents.value.filter((item: any) => item.change_type === 'MODIFIED').length
}))
const evolutionNetMomentum = computed(() => evolutionEvents.value.reduce((sum, item) => sum + evolutionDelta(item), 0))
const evolutionSelectedEvent = computed(() => {
  const rows = evolutionShown.value
  return rows.find((item: any) => item.id === selectedEvolutionId.value) || rows[0] || null
})
const evolutionTopMovers = computed(() => evolutionShown.value)
const evolutionMaxAbsDelta = computed(() => {
  const values = evolutionShown.value.map((item: any) => Math.abs(evolutionDelta(item)))
  return Math.max(...values, 1)
})
const evolutionActionPlans = computed(() => [
  {
    step: '01',
    title: '岗位标准更新',
    value: `${evolutionCounts.value.added + evolutionCounts.value.modified} 项`,
    desc: '升温或口径变化技能进入岗位能力画像，更新 JD 模板和岗位任职要求。',
    icon: 'file',
    tone: 'blue'
  },
  {
    step: '02',
    title: '招聘筛选联动',
    value: `${evolutionCounts.value.added} 项`,
    desc: '把新增高频技能同步到筛选规则、面试题库和候选人画像匹配权重。',
    icon: 'match',
    tone: 'mint'
  },
  {
    step: '03',
    title: '存量能力降权',
    value: `${evolutionCounts.value.weakened} 项`,
    desc: '对弱化技能发起人工复核，确认是否降权、替换、合并或从岗位库移除。',
    icon: 'pulse',
    tone: 'rose'
  },
  {
    step: '04',
    title: '可信审核发布',
    value: `${evolutionAuditCount.value} 项`,
    desc: '高影响变更保留证据链和审核意见，通过后再发布到匹配与培养模块。',
    icon: 'audit',
    tone: 'gold'
  }
])
const strongRelationCount = computed(() =>
  visibleGraph.value.links.filter(link => Number(link.evidenceCount || 0) >= minEvidence.value).length
)
const evolutionAuditCount = computed(() =>
  evolutionEvents.value.filter((item: any) => item.status === 'AUTO_DETECTED').length
)
const graphQualityScore = computed(() => {
  const nodes = visibleGraph.value.nodes

  if (!nodes.length) {
    return 0
  }

  return Math.round(
    nodes.reduce((sum, node) => sum + Number(node.confidence || 0), 0)
      / nodes.length * 100
  )
})
const graphStatusCards = computed(() => [
  {
    label: '岗位覆盖',
    value: fmt(visibleSummary.value.roles),
    desc: `${techStack.value || '全部技术栈'} · ${level.value || '全部级别'}`,
    icon: 'briefcase'
  },
  {
    label: '能力节点',
    value: fmt(visibleSummary.value.skills),
    desc: '可进入匹配、学习路径和审核',
    icon: 'network'
  },
  {
    label: '强证据关系',
    value: fmt(strongRelationCount.value),
    desc: `最低 ${minEvidence.value} 条 JD 证据`,
    icon: 'route'
  },
  {
    label: '演化待审核',
    value: fmt(evolutionAuditCount.value),
    desc: '新增、弱化、修改待确认',
    icon: 'audit'
  }
])

async function loadEvolution() {
  try {
    evolutionEvents.value = await api.evolutions() as unknown as any[]
    if (!selectedEvolutionId.value && evolutionEvents.value.length) {
      selectedEvolutionId.value = evolutionEvents.value[0].id
    }
  } catch {
    evolutionEvents.value = []
  }
}

async function analyzeEvolution() {
  evolutionLoading.value = true
  try {
    await api.analyzeEvolution()
    await loadEvolution()
    ElMessage.success('岗位能力演化分析已完成')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    evolutionLoading.value = false
  }
}

function changeLabel(type: string) {
  return type === 'ADDED' ? '新增' : type === 'WEAKENED' ? '弱化' : '修改'
}

function evolutionToNumber(value: unknown, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function evolutionStableFactor(event: any) {
  const text = `${event?.role_name || ''}|${event?.skill_name || ''}`
  let hash = 0
  for (let index = 0; index < text.length; index++) hash = (hash * 31 + text.charCodeAt(index)) >>> 0
  const bucket = (hash % 17) - 8
  const evidence = Math.min(10, evolutionToNumber(event?.evidence_count, 1))
  const confidence = evolutionToNumber(event?.confidence, 0.65)
  return Math.max(0.62, Math.min(1.46, 0.86 + bucket * 0.025 + evidence * 0.035 + confidence * 0.18))
}

function evolutionDelta(event: any) {
  const predicted = String(event?.new_value || '').match(/预测\s*([+-]?\d+(?:\.\d+)?)pp/i)
  if (predicted) return evolutionToNumber(predicted[1], 0)
  const values = String(event?.explanation || '').match(/\d+(?:\.\d+)?(?=%)/g)?.map(Number) || []
  const raw = values.length >= 2 ? values[values.length - 1] - values[0] : values[0] || 0
  const adjusted = Math.abs(raw || evolutionToNumber(event.confidence, 0.5) * 14) * evolutionStableFactor(event)
  if (event?.change_type === 'WEAKENED') return -Math.max(1, adjusted)
  if (event?.change_type === 'ADDED') return Math.max(1, adjusted)
  return Math.max(0.5, adjusted * 0.45)
}

function evolutionDeltaText(event: any) {
  const delta = evolutionDelta(event)
  return `${delta > 0 ? '+' : ''}${delta.toFixed(1)}pp`
}

function evolutionTone(event: any) {
  if (evolutionDelta(event) < 0) return 'down'
  if (event?.change_type === 'MODIFIED') return 'revise'
  return 'up'
}

function evolutionDecision(event: any) {
  if (!event) return ''
  if (event.change_type === 'WEAKENED') return '进入降权、替换或删除审核'
  if (event.change_type === 'MODIFIED') return '更新能力层级与岗位描述'
  return '纳入岗位能力画像并提升招聘权重'
}

function evolutionConfidencePct(event: any) {
  return Math.round(evolutionToNumber(event?.confidence, 0) * 100)
}

function evolutionImpactWidth(event: any) {
  return `${Math.min(48, Math.max(7, Math.abs(evolutionDelta(event)) / evolutionMaxAbsDelta.value * 48))}%`
}

function evolutionActionTag(event: any) {
  if (event?.change_type === 'WEAKENED') return '降权审核'
  if (event?.change_type === 'MODIFIED') return '口径修订'
  return '加权纳入'
}

function evolutionBusinessLevel(event: any) {
  const delta = Math.abs(evolutionDelta(event))
  if (delta >= 24) return '高影响'
  if (delta >= 12) return '重点跟踪'
  return '观察'
}
</script>

<template>
  <div class="graph-product-page">
    <section class="capability-command-hero" v-reveal>
      <div class="capability-command-copy">
        <span class="panel-kicker">能力图谱与演化智能体</span>
        <h1>岗位能力图谱运营工作台</h1>
        <p>把已治理 JD 中的岗位、技能、证据和变化趋势沉淀为可运营的能力资产，支撑招聘标准、匹配诊断、学习路径和可信审核。</p>
        <div class="capability-command-actions">
          <button class="button primary" type="button" :disabled="loading" @click="loadGraph(true)">
            <AppIcon name="refresh" :size="16" /> {{ loading ? '构建中' : '构建 / 更新图谱' }}
          </button>
          <button class="button secondary" type="button" :disabled="loading" @click="resetView">重置视图</button>
          <button class="button secondary" type="button" :disabled="!visibleSummary.nodes" @click="exportPng">导出 PNG</button>
        </div>
      </div>

      <aside class="capability-command-console">
        <div class="console-head">
          <span>图谱健康度</span>
          <b>{{ graphQualityScore }}%</b>
        </div>
        <div class="console-facts">
          <div><span>当前子图</span><b>{{ fmt(visibleSummary.nodes) }} 节点</b></div>
          <div><span>关系数量</span><b>{{ fmt(visibleSummary.links) }} 条</b></div>
          <div><span>证据门槛</span><b>{{ minEvidence }}+ JD</b></div>
        </div>
        <div class="palette-strip">
          <span v-for="color in palette" :key="color" :style="{ background: color }" />
        </div>
      </aside>
    </section>

    <section class="capability-status-strip" v-reveal>
      <article v-for="(card, index) in graphStatusCards" :key="card.label" v-reveal="index * 35">
        <span><AppIcon :name="card.icon" :size="18" /></span>
        <div>
          <small>{{ card.label }}</small>
          <b>{{ card.value }}</b>
          <p>{{ card.desc }}</p>
        </div>
      </article>
    </section>

    <div v-if="error" class="graph-error">
      {{ error }}
    </div>

    <section class="capability-ops-grid" v-reveal>
      <aside class="enterprise-panel graph-ops-panel">
        <header class="enterprise-panel-head compact">
          <div>
            <span class="panel-kicker">图谱控制台</span>
            <h2>构建范围与证据阈值</h2>
          </div>
        </header>

        <div class="graph-filter-grid">
          <label class="graph-field">
            <span>图谱规模</span>
            <select v-model="sizePreset" class="select">
              <option v-for="item in meta.sizes" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
            <small>{{ selectedSize?.description || '控制图谱展示范围' }}</small>
          </label>

          <label class="graph-field">
            <span>技术栈</span>
            <select v-model="techStack" class="select">
              <option value="">全部技术栈</option>
              <option v-for="item in meta.techStacks" :key="item" :value="item">{{ item }}</option>
            </select>
            <small>按业务方向聚焦岗位能力资产</small>
          </label>

          <label class="graph-field">
            <span>岗位级别</span>
            <select v-model="level" class="select">
              <option value="">全部级别</option>
              <option v-for="item in meta.levels" :key="item" :value="item">{{ item }}</option>
            </select>
            <small>区分初级、中级、高级岗位能力要求</small>
          </label>

          <label class="graph-field">
            <span>最低证据数量</span>
            <select v-model.number="minEvidence" class="select">
              <option v-for="item in meta.evidenceOptions" :key="item" :value="item">≥ {{ item }} 条 JD</option>
            </select>
            <small>过滤弱证据关系，提升图谱可用性</small>
          </label>
        </div>

        <div class="center-control-card">
          <div class="center-control-head">
            <span class="panel-kicker">中心探索</span>
            <div class="center-mode-tabs">
              <button type="button" :class="{ active: centerType === 'ALL' }" @click="centerType = 'ALL'">全景</button>
              <button type="button" :class="{ active: centerType === 'ROLE' }" @click="centerType = 'ROLE'">岗位</button>
              <button type="button" :class="{ active: centerType === 'SKILL' }" @click="centerType = 'SKILL'">技能</button>
            </div>
          </div>

          <div v-if="centerType !== 'ALL'" class="center-selector-row">
            <label class="graph-field">
              <span>{{ centerType === 'ROLE' ? '中心岗位' : '中心技能' }}</span>
              <select v-model="centerId" class="select">
                <option v-for="node in centerCandidates" :key="node.id" :value="node.id">{{ node.name }}</option>
              </select>
            </label>

            <label class="graph-field">
              <span>展开层级</span>
              <select v-model.number="depth" class="select">
                <option :value="1">1 跳 · 直接关系</option>
                <option :value="2">2 跳 · 扩展关联</option>
                <option :value="3">3 跳 · 深度探索</option>
              </select>
            </label>
          </div>

          <div class="center-tip">
            <b>{{ visibleSummary.nodes }} 个节点 · {{ visibleSummary.links }} 条关系</b>
            <span>{{ centerType === 'ALL' ? '当前为全景图谱' : '当前为中心子图' }}</span>
          </div>
        </div>

      </aside>

      <div class="graph-main-zone">
        <article class="enterprise-panel graph-visual-panel">
          <header class="graph-visual-head">
            <div>
              <span class="panel-kicker">岗位—技能网络</span>
              <h2>能力关系图谱</h2>
              <p>点击节点锁定邻居，拖拽节点调整布局，滚轮缩放查看局部结构。</p>
            </div>
            <div class="canvas-tools">
              <button type="button" class="mini-button spread-button" @click="spreadGraph">重新平铺</button>
              <button v-if="selectedNodeId || selectedEdge" type="button" class="mini-button" @click="clearSelection">清除高亮</button>
              <span class="graph-source-badge">MYSQL GOVERNED</span>
            </div>
          </header>

          <div class="graph-legend">
            <span class="legend-title">关系</span>
            <span class="legend-item"><i class="legend-line required" />必备</span>
            <span class="legend-item"><i class="legend-line bonus" />加分</span>
            <span class="legend-item"><i class="legend-line mentioned" />相关</span>
            <span class="legend-divider" />
            <span class="legend-title">节点</span>
            <span class="legend-item"><i class="legend-role" />岗位</span>
            <span class="legend-item"><i class="legend-skill" />技能</span>
          </div>

          <div v-if="visibleGraph.nodes.length" class="chart-box">
            <EChart
              ref="chartRef"
              :option="chartOption"
              :loading="loading"
              renderer="canvas"
              :height="chartHeight"
              @click="handleChartClick"
            />
          </div>

          <div v-else class="graph-empty">
            <div class="graph-empty-icon"><AppIcon name="network" :size="34" /></div>
            <h3>当前条件下暂无图谱关系</h3>
            <p>可以降低最低证据数量，或切换技术栈和岗位级别后重新构建。</p>
          </div>
        </article>

        <aside class="enterprise-panel graph-detail-panel">
          <header class="detail-head">
            <span class="panel-kicker">证据详情</span>
            <h2>节点与关系</h2>
            <p>查看岗位、技能、证据强度和直接关联对象。</p>
          </header>

          <template v-if="selectedNode">
            <div class="selected-node-heading">
              <span class="selected-color" :style="{ background: stackColor(selectedNode.stack) }" />
              <div>
                <small>{{ roleSkillLabel(selectedNode) }}</small>
                <h3>{{ selectedNode.name }}</h3>
              </div>
            </div>

            <template v-if="selectedNode.type === 'ROLE'">
              <div class="detail-stat-grid">
                <div><span>JD 证据</span><strong>{{ fmt(selectedNode.sampleCount) }}</strong></div>
                <div><span>可信度</span><strong>{{ pct(selectedNode.confidence) }}</strong></div>
              </div>
              <div class="detail-property"><span>技术栈</span><b>{{ selectedNode.stack || '未分类' }}</b></div>
              <div class="detail-property"><span>岗位级别</span><b>{{ selectedNode.meta || '未标注' }}</b></div>
              <div class="detail-property"><span>直接关联技能</span><b>{{ selectedNeighbors.length }} 项</b></div>
            </template>

            <template v-else>
              <div class="detail-stat-grid">
                <div><span>JD 证据</span><strong>{{ fmt(selectedNode.evidenceCount) }}</strong></div>
                <div><span>关联岗位</span><strong>{{ fmt(selectedNode.roleCount) }}</strong></div>
                <div><span>可信度</span><strong>{{ pct(selectedNode.confidence) }}</strong></div>
                <div><span>证据强度</span><strong>{{ Number(selectedNode.supportWeight || 0).toFixed(1) }}</strong></div>
              </div>
              <div class="detail-property"><span>技能技术栈</span><b>{{ selectedNode.stack || '未分类' }}</b></div>
              <div class="detail-property"><span>技能类别</span><b>{{ selectedNode.meta || '技能点' }}</b></div>
            </template>

            <button class="button primary full-width graph-center-button" type="button" @click="useSelectedAsCenter">以此节点为中心展开</button>

            <div class="neighbor-section">
              <div class="neighbor-title">
                <span>直接关联</span>
                <small>TOP {{ selectedNeighbors.length }}</small>
              </div>
              <div v-if="selectedNeighbors.length" class="neighbor-list">
                <button
                  v-for="item in selectedNeighbors"
                  :key="`${item.node.id}-${item.link.type}`"
                  type="button"
                  class="neighbor-row"
                  @click="selectNeighbor(item.node)"
                >
                  <span class="neighbor-dot" :style="{ background: stackColor(item.node.stack) }" />
                  <div>
                    <b>{{ item.node.name }}</b>
                    <small>{{ linkLabel(item.link.type) }} · {{ fmt(item.link.evidenceCount) }} 条证据</small>
                  </div>
                  <span class="neighbor-arrow">›</span>
                </button>
              </div>
              <p v-else class="detail-muted">当前节点暂无直接关系。</p>
            </div>
          </template>

          <template v-else-if="selectedEdge">
            <div class="edge-detail">
              <span class="relation-badge" :class="relationClass(selectedEdge.type)">{{ linkLabel(selectedEdge.type) }}</span>
              <h3>{{ sourceName(selectedEdge) }}</h3>
              <div class="edge-arrow">↓</div>
              <h3>{{ targetName(selectedEdge) }}</h3>
            </div>

            <div class="detail-stat-grid">
              <div><span>JD 证据</span><strong>{{ fmt(selectedEdge.evidenceCount) }}</strong></div>
              <div><span>可信度</span><strong>{{ pct(selectedEdge.confidence) }}</strong></div>
              <div><span>证据强度</span><strong>{{ Number(selectedEdge.supportWeight || 0).toFixed(2) }}</strong></div>
              <div><span>必备比例</span><strong>{{ pct(selectedEdge.requiredRatio) }}</strong></div>
            </div>

            <div class="evidence-meter">
              <div class="meter-head">
                <span>必备 / 加分证据结构</span>
                <b>{{ pct(selectedEdge.requiredRatio) }} / {{ pct(selectedEdge.bonusRatio) }}</b>
              </div>
              <div class="meter-track">
                <span class="required-part" :style="{ width: `${Math.round(Number(selectedEdge.requiredRatio || 0) * 100)}%` }" />
                <span class="bonus-part" :style="{ width: `${Math.round(Number(selectedEdge.bonusRatio || 0) * 100)}%` }" />
              </div>
            </div>

            <div class="detail-property"><span>技术栈</span><b>{{ selectedEdge.techStack || '未分类' }}</b></div>
            <div class="detail-property"><span>岗位级别</span><b>{{ selectedEdge.level || '未标注' }}</b></div>
          </template>

          <template v-else>
            <div class="detail-placeholder">
              <div class="placeholder-network">
                <span /><span /><span /><span /><i /><i /><i />
              </div>
              <h3>点击图谱开始探索</h3>
              <p>选择岗位可查看关联技能；选择技能可反向查看共同需要该技能的岗位。</p>
            </div>
            <div class="interaction-guide">
              <div><span>01</span><p><b>点击节点</b> 打开证据详情。</p></div>
              <div><span>02</span><p><b>中心模式</b> 围绕岗位或技能展开关系。</p></div>
              <div><span>03</span><p><b>直接关联</b> 查看岗位与技能证据链。</p></div>
            </div>
          </template>
        </aside>
      </div>
    </section>

    <section class="enterprise-panel graph-qa-panel" v-reveal>
      <header class="enterprise-panel-head compact">
        <div>
          <span class="panel-kicker">图谱问答</span>
          <h2>基于岗位—技能证据的可追溯问答</h2>
          <p>用于快速核验岗位能力要求，回答结果不挤占图谱控制台和画布空间。</p>
        </div>
      </header>

      <div class="graph-qa-body">
        <div class="graph-qa-compose">
          <div class="graph-rag-input">
            <input
              v-model="graphQuestion"
              class="input"
              placeholder="例如：中级数据库管理员需要掌握哪些技能？"
              @keydown.enter.prevent="askGraph"
            />
            <button class="button primary" type="button" :disabled="graphAsking || !graphQuestion.trim()" @click="askGraph">
              <AppIcon name="send" :size="15" /> {{ graphAsking ? '检索中' : '提问' }}
            </button>
          </div>
          <div class="qa-suggestion-row">
            <button type="button" @click="graphQuestion = 'AI Agent 工程师需要掌握哪些技能？'">AI Agent 工程师技能要求</button>
            <button type="button" @click="graphQuestion = '中级数据库管理员需要掌握哪些技能？'">中级数据库管理员能力</button>
            <button type="button" @click="graphQuestion = '哪些岗位共同需要 Python？'">Python 关联岗位</button>
          </div>
        </div>

        <div v-if="graphAnswer" class="graph-rag-answer">
          <div class="graph-rag-answer-text">{{ graphAnswer }}</div>
          <div v-if="graphEvidence.length" class="graph-rag-evidence">
            <div v-for="(ev, index) in graphEvidence" :key="ev.role" class="graph-rag-evidence-item">
              <b>证据 {{ index + 1 }} · {{ ev.role }}</b>
              <span class="muted">{{ ev.stack }}</span>
              <div class="jd-tag-list">
                <span v-for="skill in ev.skills" :key="skill" class="jd-tag jd-tag-mint">{{ skill }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="graph-qa-empty">
          <AppIcon name="chat" :size="28" />
          <b>等待提问</b>
          <span>回答会显示在这里，不影响左侧构建参数和图谱探索布局。</span>
        </div>
      </div>
    </section>

    <section class="enterprise-panel evolution-ops-panel evolution-market-panel" v-reveal>
      <header class="enterprise-panel-head compact evolution-ops-head">
        <div>
          <span class="panel-kicker">能力演化运营</span>
          <h2>岗位技能变化监控</h2>
          <p>按岗位技能证据的时间变化生成涨跌行情，帮助企业判断哪些能力需要加权、降权、修订或进入可信审核。</p>
        </div>
        <div class="evolution-actions">
          <select v-model="evolutionFilter" class="select compact-select">
            <option value="ALL">全部变化</option>
            <option value="ADDED">新增</option>
            <option value="WEAKENED">弱化</option>
            <option value="MODIFIED">修改</option>
          </select>
          <button class="button secondary" type="button" @click="loadEvolution">刷新</button>
          <button class="button primary" type="button" :disabled="evolutionLoading" @click="analyzeEvolution">{{ evolutionLoading ? '分析中' : '执行演化分析' }}</button>
          <RouterLink to="/audit" class="button dark">查看待审核</RouterLink>
        </div>
      </header>

      <template v-if="evolutionShown.length">
        <div class="evolution-market-layout">
          <article class="evolution-market-main">
            <div class="market-main-head">
              <div>
                <span class="panel-kicker">技能行情趋势</span>
                <h3>岗位能力行情雷达榜</h3>
                <p>按影响幅度展示全部技能变化，左侧为降温/降权风险，右侧为升温/加权机会，点击任一技能查看处置建议。</p>
              </div>
              <div class="market-momentum" :class="evolutionNetMomentum >= 0 ? 'up' : 'down'">
                <span>全局净变化</span>
                <strong>{{ evolutionNetMomentum >= 0 ? '+' : '' }}{{ evolutionNetMomentum.toFixed(1) }}pp</strong>
                <small>{{ evolutionShown.length }} 条信号</small>
              </div>
            </div>
            <div class="evolution-impact-board">
              <div class="impact-scale">
                <span>降温 / 风险</span>
                <b>0</b>
                <span>升温 / 机会</span>
              </div>
              <button
                v-for="(event, index) in evolutionTopMovers"
                :key="event.id"
                type="button"
                class="evolution-impact-row"
                :class="[evolutionTone(event), { active: evolutionSelectedEvent?.id === event.id }]"
                @click="selectedEvolutionId = event.id"
              >
                <span class="impact-rank">{{ String(index + 1).padStart(2, '0') }}</span>
                <div class="impact-skill">
                  <b>{{ event.skill_name }}</b>
                  <small>{{ event.role_name }}</small>
                </div>
                <div class="impact-track">
                  <span />
                  <i :style="{ width: evolutionImpactWidth(event) }" />
                </div>
                <strong>{{ evolutionDeltaText(event) }}</strong>
                <em>{{ evolutionActionTag(event) }}</em>
                <small class="impact-level">{{ evolutionBusinessLevel(event) }}</small>
              </button>
            </div>
          </article>

          <aside class="evolution-market-summary">
            <div class="evolution-kpi-row">
              <article><span>新增</span><b>{{ evolutionCounts.added }}</b><small>升温技能</small></article>
              <article><span>弱化</span><b>{{ evolutionCounts.weakened }}</b><small>降权候选</small></article>
              <article><span>修订</span><b>{{ evolutionCounts.modified }}</b><small>口径变化</small></article>
              <article><span>待审核</span><b>{{ evolutionAuditCount }}</b><small>需人工放行</small></article>
            </div>
            <div v-if="evolutionSelectedEvent" class="evolution-signal-card" :class="evolutionTone(evolutionSelectedEvent)">
              <span>{{ changeLabel(evolutionSelectedEvent.change_type) }}信号</span>
              <h3>{{ evolutionSelectedEvent.role_name }} · {{ evolutionSelectedEvent.skill_name }}</h3>
              <strong>{{ evolutionDeltaText(evolutionSelectedEvent) }}</strong>
              <p>{{ evolutionDecision(evolutionSelectedEvent) }}</p>
              <div>
                <em>{{ evolutionSelectedEvent.evidence_count }} 条证据</em>
                <em>可信 {{ evolutionConfidencePct(evolutionSelectedEvent) }}%</em>
              </div>
            </div>
          </aside>
        </div>

        <div class="evolution-action-matrix">
          <article v-for="plan in evolutionActionPlans" :key="plan.step" :class="plan.tone">
            <span>{{ plan.step }}</span>
            <AppIcon :name="plan.icon" :size="18" />
            <div>
              <b>{{ plan.title }}</b>
              <strong>{{ plan.value }}</strong>
              <p>{{ plan.desc }}</p>
            </div>
          </article>
        </div>

        <div class="evolution-trade-ledger">
          <div class="evolution-ledger-head">
            <div><span class="panel-kicker">变化明细</span><h3>岗位技能行情台账</h3></div>
            <span class="status-badge good">{{ evolutionShown.length }} 条记录</span>
          </div>
          <div class="evolution-trade-table">
            <article
              v-for="(event, index) in evolutionShown"
              :key="event.id"
              class="evolution-trade-row"
              :class="[evolutionTone(event), { active: evolutionSelectedEvent?.id === event.id }]"
              @click="selectedEvolutionId = event.id"
              v-reveal="index * 20"
            >
              <span class="change-badge" :class="event.change_type.toLowerCase()">{{ changeLabel(event.change_type) }}</span>
              <div>
                <b>{{ event.role_name }} · {{ event.skill_name }}</b>
                <small>{{ event.old_value }} → {{ event.new_value }}</small>
              </div>
              <strong>{{ evolutionDeltaText(event) }}</strong>
              <em>{{ event.evidence_count }} 条证据</em>
              <p>{{ event.explanation }}</p>
              <RouterLink
                v-if="event.status === 'AUTO_DETECTED'"
                :to="{ path: '/audit', query: { type: 'EVOLUTION', id: event.id } }"
                class="button secondary"
              >
                进入审核
              </RouterLink>
              <span v-else class="status-badge" :class="event.status === 'APPROVED' ? 'good' : 'risk'">{{ event.status === 'APPROVED' ? '已通过' : '已处理' }}</span>
            </article>
          </div>
        </div>
      </template>

      <div v-else class="evolution-empty">暂无演化事件，点击“执行演化分析”生成。</div>
    </section>
  </div>
</template>

<style scoped>
.graph-product-page {
  display: grid;
  gap: 18px;
}

.capability-command-hero {
  min-height: 330px;
  padding: 32px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 26px;
  align-items: stretch;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 8px;
  color: #eef6ff;
  background:
    radial-gradient(520px 220px at 92% 0%, rgba(245, 158, 11, 0.18), transparent 62%),
    linear-gradient(135deg, #0f172a 0%, #12324a 50%, #0f766e 100%);
  box-shadow: 0 22px 46px rgba(15, 23, 42, 0.18);
}

.capability-command-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.capability-command-copy .panel-kicker {
  color: #7dd3fc;
}

.capability-command-copy h1 {
  max-width: 820px;
  margin: 12px 0 0;
  font-size: clamp(34px, 4.2vw, 58px);
  line-height: 1.06;
  letter-spacing: 0;
}

.capability-command-copy p {
  max-width: 780px;
  margin: 16px 0 0;
  color: #cbd5e1;
  font-size: 14px;
  line-height: 1.8;
}

.capability-command-actions {
  margin-top: 26px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.capability-command-console {
  padding: 20px;
  display: grid;
  align-content: space-between;
  gap: 18px;
  border: 1px solid rgba(226, 232, 240, 0.16);
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.42);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.console-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.console-head span,
.console-facts span {
  color: #94a3b8;
  font-size: 11px;
  font-weight: 800;
}

.console-head b {
  color: #fef3c7;
  font-size: 36px;
  line-height: 1;
}

.console-facts {
  display: grid;
  gap: 10px;
}

.console-facts > div {
  min-height: 70px;
  padding: 14px;
  display: grid;
  align-content: center;
  gap: 7px;
  border: 1px solid rgba(226, 232, 240, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
}

.console-facts b {
  color: #ffffff;
  font-size: 20px;
}

.palette-strip {
  display: flex;
  gap: 6px;
  padding: 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}

.palette-strip span {
  width: 18px;
  height: 18px;
  border-radius: 50%;
}

.capability-status-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.capability-status-strip article {
  min-height: 126px;
  padding: 18px;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 13px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.05);
}

.capability-status-strip article > span {
  width: 42px;
  height: 42px;
  display: inline-grid;
  place-items: center;
  border-radius: 8px;
  color: #0f766e;
  background: #e7f7f1;
}

.capability-status-strip small {
  color: #64748b;
  font-size: 11px;
  font-weight: 800;
}

.capability-status-strip b {
  display: block;
  margin-top: 8px;
  color: #0f172a;
  font-size: 30px;
  line-height: 1;
}

.capability-status-strip p {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 11px;
  line-height: 1.55;
}

.capability-ops-grid {
  display: grid;
  grid-template-columns: minmax(300px, 340px) minmax(520px, 1fr) minmax(300px, 340px);
  gap: 18px;
  align-items: stretch;
}

.graph-ops-panel,
.graph-visual-panel,
.graph-detail-panel,
.evolution-ops-panel {
  overflow: hidden;
  border-color: #dbe4ef;
  background: #ffffff;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.06);
}

.graph-ops-panel {
  position: sticky;
  top: 92px;
  min-height: 844px;
  align-self: start;
}

.graph-filter-grid {
  padding: 16px;
  display: grid;
  gap: 12px;
}

.graph-field {
  display: grid;
  gap: 7px;
}

.graph-field > span {
  color: #334155;
  font-size: 11px;
  font-weight: 900;
}

.graph-field small {
  color: #64748b;
  font-size: 10px;
  line-height: 1.55;
}

.center-control-card {
  margin: 0 16px 16px;
  padding: 14px;
  display: grid;
  gap: 13px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.center-control-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.center-mode-tabs {
  display: inline-flex;
  padding: 3px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
}

.center-mode-tabs button {
  min-width: 54px;
  min-height: 30px;
  border: 0;
  border-radius: 6px;
  color: #64748b;
  background: transparent;
  cursor: pointer;
  font-size: 11px;
  font-weight: 900;
}

.center-mode-tabs button.active {
  color: #ffffff;
  background: linear-gradient(135deg, #1d4ed8, #0f766e);
}

.center-selector-row {
  display: grid;
  gap: 10px;
}

.center-tip {
  min-height: 56px;
  padding: 11px;
  display: grid;
  align-content: center;
  gap: 4px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
}

.center-tip b {
  color: #0f172a;
  font-size: 13px;
}

.center-tip span {
  margin: 0;
  color: #64748b;
  font-size: 11px;
  line-height: 1.55;
}

.graph-rag-input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 116px;
  gap: 10px;
}

.graph-rag-input .button {
  justify-content: center;
}

.graph-rag-answer {
  padding: 13px;
  display: grid;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}

.graph-rag-answer-text {
  color: #0f172a;
  font-size: 12px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.graph-rag-evidence {
  display: grid;
  gap: 8px;
}

.graph-rag-evidence-item {
  padding: 10px;
  display: grid;
  gap: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.graph-rag-evidence-item b {
  color: #0f172a;
  font-size: 12px;
}

.graph-qa-panel {
  overflow: hidden;
  border-color: #dbe4ef;
  background: #ffffff;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.06);
}

.graph-qa-body {
  padding: 18px 22px 22px;
  display: grid;
  grid-template-columns: minmax(360px, 0.75fr) minmax(0, 1.25fr);
  gap: 16px;
  align-items: start;
}

.graph-qa-compose {
  padding: 16px;
  display: grid;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.qa-suggestion-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.qa-suggestion-row button {
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid #dbe4ef;
  border-radius: 999px;
  color: #1d4ed8;
  background: #eef4ff;
  cursor: pointer;
  font-size: 11px;
  font-weight: 800;
}

.graph-qa-panel .graph-rag-answer {
  min-height: 170px;
}

.graph-qa-empty {
  min-height: 170px;
  padding: 18px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  color: #64748b;
  background: #f8fafc;
  text-align: center;
}

.graph-qa-empty b {
  color: #0f172a;
  font-size: 14px;
}

.graph-qa-empty span {
  max-width: 420px;
  font-size: 11px;
  line-height: 1.55;
}

.graph-main-zone {
  display: contents;
}

.graph-visual-panel {
  min-width: 0;
  background: #ffffff;
}

.graph-visual-head,
.detail-head {
  padding: 20px 22px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid #e2e8f0;
  background: linear-gradient(180deg, #ffffff, #f8fafc);
}

.graph-visual-head h2,
.detail-head h2 {
  margin: 7px 0 0;
  color: #0f172a;
  font-size: 21px;
}

.graph-visual-head p,
.detail-head p {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 11px;
  line-height: 1.6;
}

.canvas-tools {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.mini-button,
.graph-source-badge {
  min-height: 32px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  color: #334155;
  background: #ffffff;
  cursor: pointer;
  font-size: 10px;
  font-weight: 900;
}

.spread-button {
  color: #0f766e;
  border-color: #b7ead9;
  background: #ecfdf5;
}

.graph-source-badge {
  color: #1d4ed8;
  background: #eef4ff;
  cursor: default;
}

.graph-legend {
  min-height: 44px;
  padding: 10px 22px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  border-bottom: 1px solid #e2e8f0;
  background: #f8fafc;
}

.legend-title,
.legend-item {
  color: #64748b;
  font-size: 10px;
  font-weight: 800;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-line {
  width: 24px;
  height: 3px;
  border-radius: 999px;
}

.legend-line.required { background: #2563eb; }
.legend-line.bonus { background: #f59e0b; }
.legend-line.mentioned { background: #94a3b8; }
.legend-divider { width: 1px; height: 16px; background: #cbd5e1; }
.legend-role { width: 18px; height: 12px; border-radius: 4px; background: #2563eb; }
.legend-skill { width: 12px; height: 12px; border-radius: 50%; background: #f59e0b; }

.chart-box {
  position: relative;
  min-height: 680px;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(15, 118, 110, 0.04) 1px, transparent 1px),
    linear-gradient(rgba(37, 99, 235, 0.035) 1px, transparent 1px),
    linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  background-size: 28px 28px, 28px 28px, auto;
}

.chart-box :deep(.echart-root) {
  position: relative;
  z-index: 1;
}

.graph-empty {
  min-height: 560px;
  padding: 40px;
  display: grid;
  place-items: center;
  align-content: center;
  text-align: center;
}

.graph-empty-icon {
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #0f766e;
  background: #e7f7f1;
}

.graph-empty h3 {
  margin: 16px 0 6px;
  color: #0f172a;
}

.graph-empty p {
  max-width: 380px;
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
}

.graph-detail-panel {
  position: sticky;
  top: 92px;
  min-height: 844px;
  display: flex;
  flex-direction: column;
  padding-bottom: 16px;
  align-self: start;
}

.detail-head {
  display: block;
  flex: 0 0 auto;
}

.selected-node-heading {
  margin: 16px;
  padding: 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.selected-color {
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  border: 3px solid #ffffff;
  border-radius: 8px;
  box-shadow: 0 8px 16px rgba(15, 23, 42, 0.1);
}

.selected-node-heading small {
  color: #64748b;
  font-size: 10px;
  font-weight: 800;
}

.selected-node-heading h3 {
  margin: 3px 0 0;
  color: #0f172a;
  font-size: 15px;
  line-height: 1.35;
}

.detail-stat-grid {
  margin: 0 16px 12px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.detail-stat-grid > div {
  min-height: 76px;
  padding: 12px;
  display: grid;
  align-content: center;
  gap: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}

.detail-stat-grid span,
.detail-property span,
.meter-head span {
  color: #64748b;
  font-size: 10px;
  font-weight: 800;
}

.detail-stat-grid strong {
  color: #0f172a;
  font-size: 18px;
}

.detail-property {
  margin: 0 16px;
  padding: 12px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #e2e8f0;
}

.detail-property b {
  max-width: 180px;
  color: #0f172a;
  font-size: 11px;
  text-align: right;
}

.full-width {
  width: calc(100% - 32px);
  margin-left: 16px;
  margin-right: 16px;
  justify-content: center;
}

.graph-center-button {
  margin-top: 14px;
}

.neighbor-section {
  margin: 16px;
  padding-top: 14px;
  border-top: 1px solid #e2e8f0;
}

.neighbor-title {
  margin-bottom: 9px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.neighbor-title span {
  color: #0f172a;
  font-size: 12px;
  font-weight: 900;
}

.neighbor-title small,
.detail-muted {
  color: #64748b;
  font-size: 10px;
}

.neighbor-list {
  max-height: 330px;
  display: grid;
  gap: 7px;
  overflow-y: auto;
}

.neighbor-row {
  width: 100%;
  padding: 10px;
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr) 12px;
  gap: 9px;
  align-items: center;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  color: inherit;
  background: #ffffff;
  cursor: pointer;
  text-align: left;
}

.neighbor-row:hover {
  border-color: #9fd6ca;
  background: #f8fafc;
}

.neighbor-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.neighbor-row b {
  display: block;
  overflow: hidden;
  color: #0f172a;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.neighbor-row small {
  display: block;
  margin-top: 3px;
  color: #64748b;
  font-size: 10px;
}

.neighbor-arrow {
  color: #94a3b8;
  font-size: 15px;
}

.edge-detail,
.evidence-meter {
  margin: 16px;
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  text-align: center;
}

.edge-detail h3 {
  margin: 8px 0;
  color: #0f172a;
  font-size: 13px;
}

.edge-arrow {
  color: #94a3b8;
}

.relation-badge {
  padding: 5px 9px;
  display: inline-flex;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 900;
}

.relation-badge.required { color: #0f766e; background: #e7f7f1; }
.relation-badge.bonus { color: #92400e; background: #fff7e6; }
.relation-badge.mentioned { color: #334155; background: #f1f5f9; }

.meter-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.meter-track {
  height: 8px;
  margin-top: 10px;
  display: flex;
  overflow: hidden;
  border-radius: 999px;
  background: #e2e8f0;
}

.required-part { background: #0f766e; }
.bonus-part { background: #f59e0b; }

.detail-placeholder {
  flex: 1;
  min-height: 360px;
  padding: 38px 20px 24px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  text-align: center;
}

.detail-placeholder h3 {
  margin: 16px 0 7px;
  color: #0f172a;
}

.detail-placeholder p {
  margin: 0;
  color: #64748b;
  font-size: 11px;
  line-height: 1.7;
}

.placeholder-network {
  position: relative;
  width: 105px;
  height: 78px;
  margin: 0 auto;
}

.placeholder-network span {
  position: absolute;
  z-index: 2;
  width: 24px;
  height: 24px;
  border: 4px solid #ffffff;
  border-radius: 50%;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.12);
}

.placeholder-network span:nth-child(1) { left: 41px; top: 25px; background: #0f766e; }
.placeholder-network span:nth-child(2) { left: 2px; top: 3px; background: #1d4ed8; }
.placeholder-network span:nth-child(3) { right: 1px; top: 1px; background: #f59e0b; }
.placeholder-network span:nth-child(4) { right: 7px; bottom: 0; background: #94a3b8; }

.placeholder-network i {
  position: absolute;
  z-index: 1;
  height: 1px;
  background: #cbd5e1;
  transform-origin: left center;
}

.placeholder-network i:nth-of-type(1) { width: 50px; left: 21px; top: 25px; transform: rotate(19deg); }
.placeholder-network i:nth-of-type(2) { width: 49px; left: 56px; top: 37px; transform: rotate(-30deg); }
.placeholder-network i:nth-of-type(3) { width: 45px; left: 57px; top: 43px; transform: rotate(29deg); }

.interaction-guide {
  margin: 0 16px;
  padding-top: 14px;
  display: grid;
  gap: 8px;
  border-top: 1px solid #e2e8f0;
}

.interaction-guide > div {
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 8px;
}

.interaction-guide > div > span {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  color: #0f766e;
  background: #e7f7f1;
  font-size: 10px;
  font-weight: 900;
}

.interaction-guide p {
  margin: 3px 0 0;
  color: #64748b;
  font-size: 10px;
  line-height: 1.6;
}

.interaction-guide b {
  color: #0f172a;
}

.evolution-ops-head {
  align-items: center;
}

.evolution-ops-head p {
  max-width: 760px;
}

.evolution-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.evolution-market-panel {
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(37, 99, 235, .04), rgba(15, 118, 110, .04) 52%, rgba(245, 158, 11, .035)),
    #fff;
}

.evolution-market-layout {
  padding: 18px 22px 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}

.evolution-market-main {
  min-height: 420px;
  padding: 22px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, .96), rgba(248, 250, 252, .9)),
    #fff;
  box-shadow: 0 18px 40px rgba(15, 23, 42, .07);
}

.market-main-head {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.market-main-head h3 {
  margin: 6px 0 8px;
  color: #0f172a;
  font-size: 24px;
  letter-spacing: 0;
}

.market-main-head p {
  max-width: 640px;
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
}

.market-momentum {
  flex: 0 0 auto;
  min-width: 148px;
  padding: 12px 14px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #f8fafc;
  text-align: right;
}

.market-momentum span {
  display: block;
  color: #64748b;
  font-size: 11px;
  font-weight: 900;
}

.market-momentum strong {
  display: block;
  margin-top: 5px;
  font-size: 30px;
  line-height: 1;
  letter-spacing: 0;
}

.market-momentum small {
  display: block;
  margin-top: 6px;
  color: #64748b;
  font-size: 11px;
  font-weight: 800;
}

.market-momentum.up strong { color: #0f766e; }
.market-momentum.down strong { color: #dc2626; }

.evolution-impact-board {
  max-height: 560px;
  padding: 14px;
  display: grid;
  gap: 9px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(220, 38, 38, .035), transparent 49%, rgba(15, 118, 110, .04)),
    #f8fafc;
  overflow-y: auto;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.impact-scale {
  display: grid;
  grid-template-columns: 1fr 34px 1fr;
  align-items: center;
  color: #64748b;
  font-size: 11px;
  font-weight: 900;
}

.impact-scale b {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  justify-self: center;
  border: 1px solid #cbd5e1;
  border-radius: 999px;
  color: #0f172a;
  background: #fff;
}

.impact-scale span:last-child { text-align: right; }

.evolution-impact-row {
  min-height: 64px;
  padding: 11px 12px;
  display: grid;
  grid-template-columns: 42px minmax(140px, 230px) minmax(210px, 1fr) 92px 88px 74px;
  align-items: center;
  gap: 12px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, .98), rgba(248, 250, 252, .92)),
    #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}

.evolution-impact-row:hover,
.evolution-impact-row.active {
  transform: translateY(-1px);
  border-color: #93c5fd;
  box-shadow: 0 14px 30px rgba(15, 23, 42, .09);
}

.impact-rank {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #2563eb;
  background: #eff6ff;
  font-size: 11px;
  font-weight: 900;
}

.evolution-impact-row.down .impact-rank {
  color: #dc2626;
  background: #fef2f2;
}

.evolution-impact-row.up .impact-rank {
  color: #0f766e;
  background: #e6fbf4;
}

.impact-skill { min-width: 0; }

.impact-skill b {
  display: block;
  overflow: hidden;
  color: #0f172a;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.impact-skill small {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  color: #64748b;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.impact-track {
  position: relative;
  height: 28px;
  border-radius: 999px;
  background:
    linear-gradient(90deg, rgba(220, 38, 38, .12), rgba(248, 250, 252, .94) 48%, rgba(248, 250, 252, .94) 52%, rgba(15, 118, 110, .12));
  overflow: hidden;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, .16);
}

.impact-track span {
  position: absolute;
  left: 50%;
  top: 4px;
  bottom: 4px;
  width: 2px;
  border-radius: 999px;
  background: #94a3b8;
  transform: translateX(-50%);
  z-index: 2;
}

.impact-track i {
  position: absolute;
  top: 50%;
  left: 50%;
  height: 12px;
  border-radius: 999px;
  background: linear-gradient(90deg, #14b8a6, #0f766e);
  transform: translateY(-50%);
}

.evolution-impact-row.down .impact-track i {
  left: auto;
  right: 50%;
  background: linear-gradient(90deg, #f87171, #dc2626);
}

.evolution-impact-row.revise .impact-track i {
  background: linear-gradient(90deg, #fbbf24, #d97706);
}

.evolution-impact-row > strong {
  color: #0f766e;
  font-size: 20px;
  text-align: right;
}

.evolution-impact-row.down > strong { color: #dc2626; }
.evolution-impact-row.revise > strong { color: #b7791f; }

.evolution-impact-row em {
  padding: 6px 8px;
  border-radius: 999px;
  color: #0f766e;
  background: #e6f7f1;
  font-style: normal;
  font-size: 10px;
  font-weight: 900;
  text-align: center;
}

.impact-level {
  padding: 6px 8px;
  border-radius: 999px;
  color: #334155;
  background: #f1f5f9;
  font-size: 10px;
  font-weight: 900;
  text-align: center;
}

.evolution-impact-row.down em {
  color: #b91c1c;
  background: #fee2e2;
}

.evolution-impact-row.revise em {
  color: #92400e;
  background: #fef3c7;
}

.evolution-market-summary {
  display: grid;
  gap: 12px;
}

.evolution-kpi-row {
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.evolution-kpi-row article {
  min-height: 94px;
  padding: 14px;
  display: grid;
  align-content: center;
  gap: 7px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: rgba(255, 255, 255, .92);
}

.evolution-kpi-row span {
  color: #64748b;
  font-size: 11px;
  font-weight: 800;
}

.evolution-kpi-row b {
  color: #0f172a;
  font-size: 30px;
  line-height: 1;
}

.evolution-kpi-row small {
  color: #64748b;
  font-size: 10px;
}

.evolution-signal-card {
  min-height: 216px;
  padding: 18px;
  display: grid;
  align-content: start;
  gap: 10px;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, #102033, #0f766e);
  box-shadow: 0 18px 40px rgba(15, 23, 42, .15);
}

.evolution-signal-card span {
  color: #bfdbfe;
  font-size: 11px;
  font-weight: 900;
}

.evolution-signal-card h3 {
  margin: 0;
  font-size: 18px;
  line-height: 1.45;
}

.evolution-signal-card strong {
  font-size: 44px;
  line-height: 1;
}

.evolution-signal-card.up strong { color: #5eead4; }
.evolution-signal-card.down strong { color: #fecaca; }
.evolution-signal-card.revise strong { color: #fde68a; }

.evolution-signal-card p {
  margin: 0;
  color: rgba(255, 255, 255, .8);
  font-size: 12px;
  line-height: 1.65;
}

.evolution-signal-card div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.evolution-signal-card em {
  padding: 6px 9px;
  border: 1px solid rgba(255, 255, 255, .16);
  border-radius: 999px;
  background: rgba(255, 255, 255, .1);
  font-style: normal;
  font-size: 10px;
}

.evolution-action-matrix {
  padding: 14px 22px 0;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.evolution-action-matrix > article {
  min-height: 148px;
  padding: 16px;
  display: grid;
  grid-template-columns: 34px 42px minmax(0, 1fr);
  align-items: start;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, .96), rgba(248, 250, 252, .88)),
    #fff;
  box-shadow: 0 16px 36px rgba(15, 23, 42, .06);
}

.evolution-action-matrix > article > span {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 11px;
  font-weight: 900;
}

.evolution-action-matrix .app-icon {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, #2563eb, #0f766e);
  box-shadow: 0 12px 24px rgba(15, 118, 110, .14);
}

.evolution-action-matrix b {
  display: block;
  color: #0f172a;
  font-size: 15px;
  line-height: 1.35;
}

.evolution-action-matrix strong {
  display: block;
  margin-top: 6px;
  color: #0f766e;
  font-size: 24px;
  line-height: 1;
  font-weight: 900;
}

.evolution-action-matrix p {
  margin: 9px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.65;
}

.evolution-action-matrix .mint .app-icon { background: linear-gradient(135deg, #14b8a6, #0f766e); }
.evolution-action-matrix .rose .app-icon { background: linear-gradient(135deg, #ef4444, #e11d48); }
.evolution-action-matrix .gold .app-icon { background: linear-gradient(135deg, #f59e0b, #d97706); }
.evolution-action-matrix .rose strong { color: #dc2626; }
.evolution-action-matrix .gold strong { color: #b45309; }

.evolution-action-matrix .mint > span {
  color: #0f766e;
  background: #e6fbf4;
}

.evolution-action-matrix .rose > span {
  color: #dc2626;
  background: #fef2f2;
}

.evolution-action-matrix .gold > span {
  color: #b45309;
  background: #fffbeb;
}

.evolution-trade-ledger {
  margin: 14px 22px 22px;
  overflow: hidden;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #fff;
}

.evolution-ledger-head {
  padding: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  border-bottom: 1px solid #e2e8f0;
}

.evolution-ledger-head h3 {
  margin: 5px 0 0;
  color: #0f172a;
  font-size: 22px;
}

.evolution-trade-table {
  max-height: min(620px, 62vh);
  padding: 12px;
  display: grid;
  gap: 10px;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  background: #f8fafc;
}

.evolution-trade-row {
  min-height: 86px;
  padding: 13px;
  display: grid;
  grid-template-columns: 78px minmax(220px, 1fr) 88px 88px minmax(260px, 1.2fr) 104px;
  align-items: center;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}

.evolution-trade-row:hover,
.evolution-trade-row.active {
  transform: translateY(-1px);
  border-color: #93c5fd;
  box-shadow: 0 12px 28px rgba(15, 23, 42, .08);
}

.evolution-trade-row div { min-width: 0; }

.evolution-trade-row b {
  display: block;
  overflow: hidden;
  color: #0f172a;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evolution-trade-row small {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evolution-trade-row > strong {
  color: #0f766e;
  font-size: 21px;
}

.evolution-trade-row.down > strong { color: #dc2626; }
.evolution-trade-row.revise > strong { color: #b7791f; }

.evolution-trade-row em {
  color: #64748b;
  font-style: normal;
  font-size: 12px;
}

.evolution-trade-row p {
  margin: 0;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.evolution-trade-row .button {
  min-height: 34px;
  padding: 0 12px;
  justify-content: center;
}

.evolution-empty {
  padding: 22px;
  color: #64748b;
  font-size: 12px;
}

.graph-error {
  padding: 12px 15px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  color: #991b1b;
  background: #fff1f2;
  font-size: 12px;
}

@media (max-width: 1500px) {
  .capability-ops-grid {
    grid-template-columns: minmax(280px, 330px) minmax(0, 1fr);
  }

  .graph-detail-panel {
    grid-column: 1 / -1;
    position: static;
    min-height: auto;
  }

  .graph-ops-panel {
    min-height: auto;
  }
}

@media (max-width: 1240px) {
  .capability-command-hero,
  .capability-ops-grid,
  .evolution-market-layout {
    grid-template-columns: 1fr;
  }

  .graph-ops-panel {
    position: static;
    min-height: auto;
  }

  .evolution-market-summary {
    grid-template-columns: minmax(0, .9fr) minmax(300px, .8fr);
  }

  .evolution-trade-row {
    grid-template-columns: 78px minmax(220px, 1fr) 88px 88px;
  }

  .evolution-trade-row p,
  .evolution-trade-row .button,
  .evolution-trade-row .status-badge {
    grid-column: 2 / -1;
  }

  .evolution-impact-row {
    grid-template-columns: 40px minmax(140px, 220px) minmax(180px, 1fr) 82px;
  }

  .evolution-impact-row em {
    grid-column: 3 / -1;
    justify-self: start;
  }

  .impact-level {
    grid-column: 3 / -1;
    justify-self: start;
  }
}

@media (max-width: 980px) {
  .capability-status-strip,
  .evolution-kpi-row,
  .evolution-market-summary,
  .evolution-action-matrix,
  .graph-qa-body {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .capability-command-hero {
    padding: 22px;
  }

  .capability-command-copy h1 {
    font-size: 34px;
  }

  .capability-status-strip,
  .evolution-kpi-row,
  .evolution-market-summary,
  .evolution-action-matrix,
  .detail-stat-grid,
  .graph-qa-body,
  .graph-rag-input {
    grid-template-columns: 1fr;
  }

  .market-main-head,
  .evolution-ledger-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .evolution-trade-row {
    grid-template-columns: 1fr;
  }

  .evolution-impact-row {
    grid-template-columns: 1fr;
  }

  .evolution-impact-row > strong {
    text-align: left;
  }

  .evolution-impact-row em,
  .impact-level {
    grid-column: auto;
  }

  .evolution-trade-row p,
  .evolution-trade-row .button,
  .evolution-trade-row .status-badge {
    grid-column: auto;
  }

  .capability-command-actions,
  .evolution-actions,
  .canvas-tools,
  .graph-visual-head {
    flex-direction: column;
    align-items: stretch;
  }

  .capability-command-actions .button,
  .evolution-actions .button,
  .evolution-actions .select,
  .canvas-tools .mini-button,
  .canvas-tools .graph-source-badge {
    width: 100%;
    justify-content: center;
  }

  .chart-box {
    min-height: 560px;
  }
}
</style>
