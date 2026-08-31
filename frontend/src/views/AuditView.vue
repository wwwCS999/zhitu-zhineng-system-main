<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { api } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import TrustBadge from '@/components/TrustBadge.vue'
import AppIcon from '@/components/AppIcon.vue'
import { useEnglishThemeText } from '@/composables/useEnglishThemeText'

const route = useRoute()
const { tx, phrase } = useEnglishThemeText()
const pending = ref<any[]>([])
const history = ref<any[]>([])
const tab = ref<'queue' | 'history'>('queue')
const selectedSource = ref('ALL')
const highlightKey = ref('')
const loading = ref(false)
const decisionVisible = ref(false)
const decisionSubmitting = ref(false)
const decisionItem = ref<any | null>(null)
const historyDetail = ref<any | null>(null)
const decisionAction = ref<'APPROVE' | 'REJECT' | 'MODIFY'>('APPROVE')
const decisionForm = ref({
  basis: '',
  risk: '',
  impact: '',
  patchNote: '',
  publishScope: 'INTERNAL',
  reviewer: '竞赛管理员',
  dueDate: '',
  followUp: true
})

const sourceMeta: Record<string, any> = {
  EMERGING_ROLE: {
    agent: '岗位洞察智能体',
    title: '新岗位入库审核',
    short: '岗位入库',
    desc: '审核自动发现的新兴岗位是否可发布到企业岗位库。',
    decision: '是否纳入标准岗位库',
    icon: 'spark',
    tone: 'blue'
  },
  EVOLUTION: {
    agent: '能力图谱与演化智能体',
    title: '能力变更审核',
    short: '图谱演化',
    desc: '审核岗位能力新增、弱化或要求修改是否可写入能力图谱。',
    decision: '是否更新岗位能力图谱',
    icon: 'network',
    tone: 'mint'
  }
}

function metaOf(type: string) {
  const meta = sourceMeta[type]
  if (type === 'EMERGING_ROLE') {
    return {
      ...meta,
      agent: tx('岗位洞察智能体', 'Job Insight Agent'),
      title: tx('新岗位入库审核', 'Emerging Role Library Audit'),
      short: tx('岗位入库', 'Role Intake'),
      desc: tx('审核自动发现的新兴岗位是否可发布到企业岗位库。', 'Review whether automatically discovered emerging roles can be released to the enterprise role library.'),
      decision: tx('是否纳入标准岗位库', 'Whether to add it to the standard role library')
    }
  }
  if (type === 'EVOLUTION') {
    return {
      ...meta,
      agent: tx('能力图谱与演化智能体', 'Capability Graph & Evolution Agent'),
      title: tx('能力变更审核', 'Capability Change Audit'),
      short: tx('图谱演化', 'Graph Evolution'),
      desc: tx('审核岗位能力新增、弱化或要求修改是否可写入能力图谱。', 'Review whether added, weakened or revised capability requirements can be written into the capability graph.'),
      decision: tx('是否更新岗位能力图谱', 'Whether to update the role capability graph')
    }
  }
  return {
    agent: tx('可信审核智能体', 'Trust Audit Agent'),
    title: tx('通用审核', 'General Audit'),
    short: tx('通用', 'General'),
    desc: tx('人工确认自动化结果是否可进入生产链路。', 'Human confirmation before automated results enter production workflows.'),
    decision: tx('是否放行', 'Whether to release'),
    icon: 'audit',
    tone: 'slate'
  }
}

function parseList(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String).filter(Boolean)
  if (value == null || value === '') return []
  const text = String(value)
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean)
  } catch {
    /* not json */
  }
  return text.split(/[；;,\n]/).map(item => item.trim()).filter(Boolean)
}

function toNumber(value: unknown, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function pct(value: unknown) {
  return Math.round(toNumber(value) * 100)
}

function riskScore(item: any) {
  return toNumber(item.risk ?? item.hallucination_risk ?? (1 - toNumber(item.confidence, 0.5)), 0)
}

function trustScore(item: any) {
  return Math.max(0, Math.min(1, toNumber(item.confidence ?? (1 - riskScore(item)), 0.5)))
}

function riskLevel(item: any) {
  const risk = riskScore(item)
  if (risk >= 0.35) return { label: tx('高风险', 'High Risk'), tone: 'danger' }
  if (risk >= 0.18) return { label: tx('需复核', 'Review Required'), tone: 'warn' }
  return { label: tx('低风险', 'Low Risk'), tone: 'good' }
}

function evidenceCount(item: any) {
  return toNumber(item.evidence_count ?? item.sample_size ?? item.source_count, 0)
}

function mainDescription(item: any) {
  return phrase(item.definition || item.explanation || item.description || tx('等待人工补充审核说明。', 'Waiting for human audit notes.'))
}

function auditSubject(item: any) {
  if (item.targetType === 'EVOLUTION') return `${phrase(item.role_name || tx('岗位', 'Role'))} · ${phrase(item.skill_name || tx('能力', 'Capability'))}`
  return phrase(item.title || item.candidate_name || `${tx('审核对象', 'Audit Subject')} #${item.id}`)
}

function gateChecks(item: any) {
  if (item.targetType === 'EMERGING_ROLE') {
    return [
      { label: tx('多源样本', 'Multi-source Samples'), value: `${toNumber(item.sample_size)} ${tx('条', 'records')}`, ok: toNumber(item.sample_size) >= 30 },
      { label: tx('独立来源', 'Independent Sources'), value: `${toNumber(item.source_count)} ${tx('个', 'sources')}`, ok: toNumber(item.source_count) >= 2 },
      { label: tx('增长信号', 'Growth Signal'), value: `${pct(item.growth_rate)}%`, ok: toNumber(item.growth_rate) > 0 },
      { label: tx('幻觉风险', 'Hallucination Risk'), value: `${pct(riskScore(item))}%`, ok: riskScore(item) < 0.2 }
    ]
  }
  return [
    { label: tx('证据数量', 'Evidence Count'), value: `${toNumber(item.evidence_count)} ${tx('条', 'records')}`, ok: toNumber(item.evidence_count) >= 3 },
    { label: tx('置信度', 'Confidence'), value: `${pct(item.confidence)}%`, ok: toNumber(item.confidence) >= 0.7 },
    { label: tx('变化类型', 'Change Type'), value: phrase(item.change_type || tx('待确认', 'Pending')), ok: Boolean(item.change_type) },
    { label: tx('幻觉风险', 'Hallucination Risk'), value: `${pct(riskScore(item))}%`, ok: riskScore(item) < 0.3 }
  ]
}

function evidenceTags(item: any) {
  if (item.targetType === 'EMERGING_ROLE') {
    return [
      ...parseList(item.required_skills).slice(0, 6),
      ...parseList(item.bonus_skills).slice(0, 3),
      ...parseList(item.scenarios).slice(0, 2)
    ].slice(0, 10)
  }
  return [
    item.role_name,
    item.skill_name,
    item.change_type,
    item.old_value,
    item.new_value
  ].map(String).filter(Boolean).slice(0, 8)
}

function historyTargetType(item: any) {
  return item.target_type || item.targetType || ''
}

function actionLabel(action: string) {
  if (action === 'APPROVE') return tx('通过', 'Approve')
  if (action === 'REJECT') return tx('驳回', 'Reject')
  if (action === 'MODIFY') return tx('修订后通过', 'Approve with Revision')
  return action || tx('未知', 'Unknown')
}

function actionTone(action: string) {
  if (action === 'REJECT') return 'red'
  if (action === 'MODIFY') return 'blue'
  return 'green'
}

function parseAuditSections(comment: unknown) {
  const text = String(comment || '').trim()
  if (!text) return [{ label: tx('审核意见', 'Audit Comment'), value: tx('未填写审核意见', 'No audit comment provided') }]
  const matches = [...text.matchAll(/【([^】]+)】([\s\S]*?)(?=【[^】]+】|$)/g)]
  if (!matches.length) return [{ label: tx('审核意见', 'Audit Comment'), value: phrase(text) }]
  return matches.map(match => ({
    label: phrase(match[1].trim()),
    value: phrase(match[2].trim() || tx('未填写', 'Not filled'))
  })).filter(row => row.label)
}

function compactSectionValue(comment: unknown, preferred: string[] = ['审核结论', '审核对象', '通过依据']) {
  const sections = parseAuditSections(comment)
  return preferred
    .map(label => sections.find(section => section.label === label))
    .filter(Boolean)
    .slice(0, 2) as Array<{ label: string; value: string }>
}

function parseSnapshot(value: unknown) {
  if (!value) return {}
  if (typeof value === 'object') return value as Record<string, unknown>
  try {
    const parsed = JSON.parse(String(value))
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

function snapshotRows(value: unknown) {
  const entries = Object.entries(parseSnapshot(value))
    .filter(([, val]) => val !== null && val !== undefined && String(val) !== '')
    .slice(0, 10)
  return entries.map(([key, val]) => ({
    key,
    value: typeof val === 'object' ? JSON.stringify(val) : phrase(val)
  }))
}

function openHistoryDetail(item: any) {
  historyDetail.value = item
}

function closeHistoryDetail() {
  historyDetail.value = null
}

const sourceStats = computed(() => {
  const sources = Object.keys(sourceMeta).map(type => {
    const rows = pending.value.filter(item => item.targetType === type)
    const highRisk = rows.filter(item => riskScore(item) >= 0.18).length
    const avgTrust = rows.length
      ? Math.round(rows.reduce((sum, item) => sum + trustScore(item), 0) / rows.length * 100)
      : 0
    return { type, ...metaOf(type), count: rows.length, highRisk, avgTrust }
  })
  const allRisk = pending.value.filter(item => riskScore(item) >= 0.18).length
  const allTrust = pending.value.length
    ? Math.round(pending.value.reduce((sum, item) => sum + trustScore(item), 0) / pending.value.length * 100)
    : 0
  return [
    {
      type: 'ALL',
      agent: tx('全部智能体', 'All Agents'),
      title: tx('全量审核队列', 'Full Audit Queue'),
      short: tx('全部', 'All'),
      desc: tx('跨智能体统一排队，但审核来源和发布影响独立展示。', 'Unified cross-agent queue with independent source and release-impact display.'),
      icon: 'audit',
      tone: 'slate',
      count: pending.value.length,
      highRisk: allRisk,
      avgTrust: allTrust
    },
    ...sources
  ]
})

const visiblePending = computed(() => {
  const rows = selectedSource.value === 'ALL'
    ? pending.value
    : pending.value.filter(item => item.targetType === selectedSource.value)
  return [...rows].sort((a, b) => riskScore(b) - riskScore(a))
})

const highRiskCount = computed(() => pending.value.filter(item => riskScore(item) >= 0.18).length)
const avgTrustPercent = computed(() => sourceStats.value[0]?.avgTrust || 0)
const approvedHistory = computed(() => history.value.filter(item => item.action === 'APPROVE' || item.action === 'MODIFY').length)

const decisionActionText = computed(() => {
  if (decisionAction.value === 'REJECT') return tx('驳回', 'Reject')
  if (decisionAction.value === 'MODIFY') return tx('修订后通过', 'Approve with Revision')
  return tx('通过', 'Approve')
})

const decisionTone = computed(() => {
  if (decisionAction.value === 'REJECT') return 'reject'
  if (decisionAction.value === 'MODIFY') return 'modify'
  return 'approve'
})

const decisionTitle = computed(() => {
  const item = decisionItem.value
  return item ? `${auditSubject(item)} · ${decisionActionText.value}` : decisionActionText.value
})

const decisionChecklist = computed(() => {
  const item = decisionItem.value
  if (!item) return []
  const gates = gateChecks(item)
  const failed = gates.filter(gate => !gate.ok)
  const base = [
    { label: tx('来源智能体', 'Source Agent'), value: metaOf(item.targetType).agent },
    { label: tx('证据门禁', 'Evidence Gate'), value: failed.length ? `${failed.length} ${tx('项需说明', 'items need explanation')}` : tx('全部通过', 'All Passed') },
    { label: tx('发布影响', 'Release Impact'), value: metaOf(item.targetType).decision },
    { label: tx('风险等级', 'Risk Level'), value: riskLevel(item).label }
  ]
  return base
})

const decisionGuidance = computed(() => {
  if (decisionAction.value === 'REJECT') {
    return {
      basis: '请说明驳回的核心证据问题',
      risk: '请写明不可放行的风险点',
      impact: '建议说明退回后需要补充哪些材料'
    }
  }
  if (decisionAction.value === 'MODIFY') {
    return {
      basis: '请说明哪些证据支持修订后放行',
      risk: '请写明修订后仍需关注的风险',
      impact: '请说明修订会影响哪些字段或发布范围'
    }
  }
  return {
    basis: '请说明通过依据和关键证据来源',
    risk: '请确认低风险判断依据',
    impact: '请说明放行后进入哪条业务链路'
  }
})

async function load() {
  loading.value = true
  try {
    const [pendingRows, historyRows] = await Promise.all([api.pending(), api.auditHistory()])
    pending.value = pendingRows as unknown as any[]
    history.value = historyRows as unknown as any[]
  } finally {
    loading.value = false
  }
}

function openDecision(item: any, action: 'APPROVE' | 'REJECT' | 'MODIFY') {
  decisionItem.value = item
  decisionAction.value = action
  const meta = metaOf(item.targetType)
  const subject = auditSubject(item)
  decisionForm.value = {
    basis: action === 'REJECT'
      ? `${meta.agent}输出的“${subject}”证据未满足发布门禁。`
      : `${meta.agent}输出的“${subject}”已完成证据复核，满足当前审核门槛。`,
    risk: riskLevel(item).label === '低风险'
      ? `幻觉风险 ${pct(riskScore(item))}%，证据一致性可接受。`
      : `幻觉风险 ${pct(riskScore(item))}%，需保留人工复核意见。`,
    impact: action === 'REJECT'
      ? '暂不进入生产发布链路，退回来源智能体补充证据。'
      : `${meta.decision}，并在审计历史中保留本次人工决策。`,
    patchNote: action === 'MODIFY' ? mainDescription(item) : '',
    publishScope: action === 'REJECT' ? 'BLOCKED' : 'INTERNAL',
    reviewer: '竞赛管理员',
    dueDate: '',
    followUp: action !== 'APPROVE'
  }
  decisionVisible.value = true
}

function closeDecision() {
  if (decisionSubmitting.value) return
  decisionVisible.value = false
}

async function submitDecision() {
  const item = decisionItem.value
  if (!item) return
  if (!decisionForm.value.basis.trim() || !decisionForm.value.risk.trim() || !decisionForm.value.impact.trim()) {
    ElMessage.warning('请补全通过依据、风险判断和发布影响')
    return
  }
  const action = decisionAction.value
  const actionText = decisionActionText.value
  const comment = [
    `【审核结论】${actionText}`,
    `【审核对象】${auditSubject(item)}`,
    `【来源智能体】${metaOf(item.targetType).agent}`,
    `【通过依据】${decisionForm.value.basis.trim()}`,
    `【风险判断】${decisionForm.value.risk.trim()}`,
    `【发布影响】${decisionForm.value.impact.trim()}`,
    decisionForm.value.patchNote.trim() ? `【修订说明】${decisionForm.value.patchNote.trim()}` : '',
    `【发布范围】${decisionForm.value.publishScope}`,
    decisionForm.value.dueDate ? `【复核截止】${decisionForm.value.dueDate}` : '',
    `【后续动作】${decisionForm.value.followUp ? '需要跟踪复核' : '无需额外跟踪'}`
  ].filter(Boolean).join('\n')
  const patch = action === 'MODIFY' && decisionForm.value.patchNote.trim()
    ? item.targetType === 'EVOLUTION'
      ? { explanation: decisionForm.value.patchNote.trim() }
      : { definition: decisionForm.value.patchNote.trim() }
    : {}
  decisionSubmitting.value = true
  try {
    await api.decide(item.targetType, item.id, action, comment, patch, decisionForm.value.reviewer || '竞赛管理员')
    ElMessage.success('审核决策已记录')
    decisionVisible.value = false
    await load()
  } catch (err: any) {
    if (err?.message) ElMessage.error(err.message)
  } finally {
    decisionSubmitting.value = false
  }
}

async function applyHighlight() {
  const type = String(route.query.type || '')
  const id = String(route.query.id || '')
  if (!type || !id) return
  selectedSource.value = type
  highlightKey.value = `${type}-${id}`
  await nextTick()
  document.getElementById(`audit-${type}-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

onMounted(async () => {
  await load()
  await applyHighlight()
})
</script>

<template>
  <div class="audit-product-page">
    <section class="audit-command-center" v-reveal>
      <div class="audit-command-copy">
        <span class="match-kicker">{{ tx('可信审核智能体', 'Trust Audit Agent') }}</span>
        <h1>{{ tx('企业可信治理与发布审核台', 'Enterprise Trusted Governance & Release Audit Console') }}</h1>
        <p>{{ tx('把岗位洞察、能力演化等智能体输出分流到不同审核队列，按证据充分度、幻觉风险和发布影响做人工放行。', 'Route outputs from job insight and capability evolution agents into audit queues, with human approval based on evidence sufficiency, hallucination risk and release impact.') }}</p>
        <div class="match-command-actions">
          <button class="button secondary" type="button" :disabled="loading" @click="load">
            <AppIcon name="refresh" :size="16" />{{ loading ? tx('刷新中', 'Refreshing') : tx('刷新队列', 'Refresh Queue') }}
          </button>
          <button class="button primary" type="button" @click="tab = 'queue'">
            <AppIcon name="audit" :size="16" />{{ tx('进入审核', 'Enter Audit') }}
          </button>
        </div>
      </div>

      <aside class="audit-command-card">
        <div>
          <span>{{ tx('待审核事项', 'Pending Audit Items') }}</span>
          <strong>{{ pending.length }}</strong>
        </div>
        <div class="audit-command-meter">
          <span>{{ tx('平均可信度', 'Average Trust') }}</span>
          <b>{{ avgTrustPercent }}%</b>
        </div>
        <div class="audit-command-checks">
          <span><AppIcon name="check" :size="13" />{{ tx('来源分区', 'Source Zones') }}</span>
          <span><AppIcon name="check" :size="13" />{{ tx('证据门禁', 'Evidence Gate') }}</span>
          <span><AppIcon name="check" :size="13" />{{ tx('审计留痕', 'Audit Trail') }}</span>
        </div>
      </aside>
    </section>

    <section class="audit-kpi-strip">
      <article>
        <span>{{ tx('跨智能体队列', 'Cross-agent Queues') }}</span>
        <strong>{{ sourceStats.length - 1 }}</strong>
        <small>{{ tx('岗位洞察 / 能力演化分流审核', 'Job insight / capability evolution routed audit') }}</small>
      </article>
      <article>
        <span>{{ tx('高风险复核', 'High-risk Review') }}</span>
        <strong>{{ highRiskCount }}</strong>
        <small>{{ tx('按幻觉风险和证据不足优先处理', 'Prioritized by hallucination risk and weak evidence') }}</small>
      </article>
      <article>
        <span>{{ tx('已留痕记录', 'Audit Records') }}</span>
        <strong>{{ history.length }}</strong>
        <small>{{ tx('审核前后状态可追溯', 'Before/after status is traceable') }}</small>
      </article>
      <article>
        <span>{{ tx('放行/修订', 'Released / Revised') }}</span>
        <strong>{{ approvedHistory }}</strong>
        <small>{{ tx('进入岗位库或能力图谱发布链路', 'Released to role library or capability graph workflows') }}</small>
      </article>
    </section>

    <section class="audit-source-board" v-reveal>
      <button
        v-for="source in sourceStats"
        :key="source.type"
        type="button"
        class="audit-source-card"
        :class="[`tone-${source.tone}`, { active: selectedSource === source.type }]"
        @click="selectedSource = source.type; tab = 'queue'"
      >
        <span><AppIcon :name="source.icon" :size="17" /></span>
        <div>
          <b>{{ source.title }}</b>
          <small>{{ source.agent }}</small>
          <p>{{ source.desc }}</p>
        </div>
        <em>{{ source.count }} {{ tx('项', 'items') }}</em>
        <strong>{{ source.avgTrust }}%</strong>
      </button>
    </section>

    <section class="surface audit-workbench" v-reveal>
      <header class="surface-head audit-workbench-head">
        <div>
          <span class="eyebrow">{{ tx('审核工作台', 'Audit Workbench') }}</span>
          <h2>{{ selectedSource === 'ALL' ? tx('跨智能体审核队列', 'Cross-agent Audit Queue') : metaOf(selectedSource).title }}</h2>
          <p>{{ selectedSource === 'ALL' ? tx('按风险优先展示所有智能体待审核输出，卡片内保留来源和发布影响。', 'Show pending outputs by risk priority, with source and release impact retained in each card.') : metaOf(selectedSource).desc }}</p>
        </div>
        <div class="segmented-control">
          <button type="button" :class="{ active: tab === 'queue' }" @click="tab = 'queue'">{{ tx('审核队列', 'Audit Queue') }} {{ visiblePending.length }}</button>
          <button type="button" :class="{ active: tab === 'history' }" @click="tab = 'history'">{{ tx('审计历史', 'Audit History') }} {{ history.length }}</button>
        </div>
      </header>

      <div v-if="tab === 'queue' && visiblePending.length" class="audit-review-grid">
        <article
          v-for="(item, index) in visiblePending"
          :key="`${item.targetType}-${item.id}`"
          :id="`audit-${item.targetType}-${item.id}`"
          class="audit-review-card"
          :class="[`tone-${metaOf(item.targetType).tone}`, { 'audit-highlight': highlightKey === `${item.targetType}-${item.id}` }]"
          v-reveal="index * 35"
        >
          <header>
            <div class="audit-agent-mark">
              <span><AppIcon :name="metaOf(item.targetType).icon" :size="17" /></span>
              <div>
                <small>{{ metaOf(item.targetType).agent }}</small>
                <b>{{ metaOf(item.targetType).title }}</b>
              </div>
            </div>
            <span class="audit-risk-pill" :class="riskLevel(item).tone">{{ riskLevel(item).label }}</span>
          </header>

          <div class="audit-review-main">
            <div>
              <span class="eyebrow">{{ tx('决策事项', 'Decision Item') }}</span>
              <h3>{{ auditSubject(item) }}</h3>
              <p>{{ mainDescription(item) }}</p>
            </div>
            <aside>
              <TrustBadge :score="trustScore(item)" />
              <strong>{{ pct(riskScore(item)) }}%</strong>
              <span>{{ tx('幻觉风险', 'Hallucination Risk') }}</span>
            </aside>
          </div>

          <div class="audit-gate-grid">
            <article v-for="gate in gateChecks(item)" :key="gate.label" :class="{ pass: gate.ok }">
              <span>{{ gate.label }}</span>
              <b>{{ gate.value }}</b>
            </article>
          </div>

          <div class="audit-evidence-panel">
            <div>
              <b>{{ tx('证据摘要', 'Evidence Summary') }}</b>
              <span>{{ evidenceCount(item) }} {{ tx('条可复核信号', 'reviewable signals') }}</span>
            </div>
            <div class="skill-cloud">
              <span v-for="tag in evidenceTags(item)" :key="tag" class="tag blue">{{ phrase(tag) }}</span>
              <span v-if="!evidenceTags(item).length" class="tag">{{ tx('待补充证据', 'Evidence pending') }}</span>
            </div>
          </div>

          <div class="audit-impact-row">
            <span><AppIcon name="focus" :size="14" />{{ metaOf(item.targetType).decision }}</span>
            <span>#{{ item.id }}</span>
          </div>

          <footer>
            <button class="button primary" type="button" @click="openDecision(item, 'APPROVE')">
              <AppIcon name="check" :size="15" />{{ tx('通过', 'Approve') }}
            </button>
            <button class="button secondary" type="button" @click="openDecision(item, 'MODIFY')">
              <AppIcon name="edit" :size="15" />{{ tx('修订', 'Revise') }}
            </button>
            <button class="button danger" type="button" @click="openDecision(item, 'REJECT')">
              <AppIcon name="close" :size="15" />{{ tx('驳回', 'Reject') }}
            </button>
          </footer>
        </article>
      </div>

      <div v-else-if="tab === 'history' && history.length" class="audit-history-board">
        <button
          v-for="item in history"
          :key="item.id"
          type="button"
          class="audit-history-row"
          @click="openHistoryDetail(item)"
        >
          <div class="audit-history-source">
            <span><AppIcon :name="metaOf(historyTargetType(item)).icon" :size="15" /></span>
            <div>
              <b>{{ metaOf(historyTargetType(item)).agent }}</b>
              <small>{{ metaOf(historyTargetType(item)).title }} / #{{ item.target_id }}</small>
            </div>
          </div>
          <span class="tag" :class="actionTone(item.action)">{{ actionLabel(item.action) }}</span>
          <p>
            <span v-for="section in compactSectionValue(item.comment)" :key="section.label">
              <b>{{ section.label }}</b>{{ section.value }}
            </span>
          </p>
          <div class="audit-history-meta">
            <span>{{ tx('审核人：', 'Reviewer: ') }}{{ item.reviewer || tx('管理员', 'Admin') }}</span>
            <span>{{ tx('风险：', 'Risk: ') }}{{ pct(item.risk_score) }}%</span>
            <span>{{ item.created_at }}</span>
          </div>
          <strong>{{ tx('查看详情', 'View Details') }}</strong>
        </button>
      </div>

      <EmptyState
        v-else
        :title="tab === 'queue' ? tx('当前筛选下没有待审核项', 'No pending audit items under current filters') : tx('暂无审计记录', 'No audit records yet')"
        :description="tx('智能体输出进入岗位库、能力图谱或匹配链路前，需要完成来源区分、证据复核和人工留痕。', 'Before agent outputs enter the role library, capability graph or matching workflows, source separation, evidence review and audit trails are required.')"
      />
    </section>

    <teleport to="body">
      <div v-if="historyDetail" class="audit-decision-backdrop" @click.self="closeHistoryDetail">
        <section class="audit-history-modal" role="dialog" aria-modal="true" aria-label="审计历史详情">
          <header class="audit-decision-head">
            <div>
              <span class="eyebrow">审计历史详情</span>
              <h2>{{ metaOf(historyTargetType(historyDetail)).title }} · {{ actionLabel(historyDetail.action) }}</h2>
              <p>完整保留人工结论、证据依据、风险判断、发布影响和审核前后快照。</p>
            </div>
            <button class="modal-close" type="button" @click="closeHistoryDetail">
              <AppIcon name="close" :size="19" />
            </button>
          </header>

          <div class="audit-history-detail-body">
            <aside class="audit-history-detail-summary">
              <div class="audit-decision-agent">
                <span><AppIcon :name="metaOf(historyTargetType(historyDetail)).icon" :size="18" /></span>
                <div>
                  <small>{{ metaOf(historyTargetType(historyDetail)).agent }}</small>
                  <b>{{ metaOf(historyTargetType(historyDetail)).title }}</b>
                </div>
              </div>
              <div class="audit-history-result-card" :class="actionTone(historyDetail.action)">
                <span>审核结论</span>
                <strong>{{ actionLabel(historyDetail.action) }}</strong>
                <small>目标 #{{ historyDetail.target_id }}</small>
              </div>
              <div class="audit-decision-checklist">
                <article>
                  <span>审核人</span>
                  <b>{{ historyDetail.reviewer || '管理员' }}</b>
                </article>
                <article>
                  <span>风险</span>
                  <b>{{ pct(historyDetail.risk_score) }}%</b>
                </article>
                <article>
                  <span>来源类型</span>
                  <b>{{ historyTargetType(historyDetail) }}</b>
                </article>
                <article>
                  <span>记录时间</span>
                  <b>{{ historyDetail.created_at }}</b>
                </article>
              </div>
            </aside>

            <main class="audit-history-detail-main">
              <section class="audit-history-sections">
                <article v-for="section in parseAuditSections(historyDetail.comment)" :key="section.label">
                  <span>{{ section.label }}</span>
                  <p>{{ section.value }}</p>
                </article>
              </section>

              <section class="audit-snapshot-compare">
                <article>
                  <header>
                    <span>审核前快照</span>
                    <b>{{ snapshotRows(historyDetail.before_json).length }} 字段</b>
                  </header>
                  <dl>
                    <template v-for="row in snapshotRows(historyDetail.before_json)" :key="row.key">
                      <dt>{{ row.key }}</dt>
                      <dd>{{ row.value }}</dd>
                    </template>
                  </dl>
                </article>
                <article>
                  <header>
                    <span>审核后快照</span>
                    <b>{{ snapshotRows(historyDetail.after_json).length }} 字段</b>
                  </header>
                  <dl>
                    <template v-for="row in snapshotRows(historyDetail.after_json)" :key="row.key">
                      <dt>{{ row.key }}</dt>
                      <dd>{{ row.value }}</dd>
                    </template>
                  </dl>
                </article>
              </section>
            </main>
          </div>

          <footer class="audit-decision-foot">
            <span>该记录用于企业审计、发布复盘和来源追溯。</span>
            <div>
              <button class="button primary" type="button" @click="closeHistoryDetail">关闭详情</button>
            </div>
          </footer>
        </section>
      </div>

      <div v-if="decisionVisible && decisionItem" class="audit-decision-backdrop" @click.self="closeDecision">
        <section class="audit-decision-modal" :class="`tone-${decisionTone}`" role="dialog" aria-modal="true" aria-label="结构化人工审核">
          <header class="audit-decision-head">
            <div>
              <span class="eyebrow">人工审核决策</span>
              <h2>{{ decisionTitle }}</h2>
              <p>按企业发布前门禁记录证据依据、风险判断、修订范围和后续动作。</p>
            </div>
            <button class="modal-close" type="button" @click="closeDecision">
              <AppIcon name="close" :size="19" />
            </button>
          </header>

          <div class="audit-decision-body">
            <aside class="audit-decision-summary">
              <div class="audit-readonly-banner">
                <span>系统证据快照</span>
                <b>只读，不随人工意见被改写</b>
              </div>

              <div class="audit-decision-agent">
                <span><AppIcon :name="metaOf(decisionItem.targetType).icon" :size="18" /></span>
                <div>
                  <small>{{ metaOf(decisionItem.targetType).agent }}</small>
                  <b>{{ metaOf(decisionItem.targetType).title }}</b>
                </div>
              </div>

              <div class="audit-decision-score">
                <strong>{{ Math.round(trustScore(decisionItem) * 100) }}%</strong>
                <span>当前可信度</span>
                <em :class="riskLevel(decisionItem).tone">{{ riskLevel(decisionItem).label }}</em>
              </div>

              <div class="audit-decision-checklist">
                <article v-for="row in decisionChecklist" :key="row.label">
                  <span>{{ row.label }}</span>
                  <b>{{ row.value }}</b>
                </article>
              </div>

              <div class="audit-decision-tags">
                <span v-for="tag in evidenceTags(decisionItem).slice(0, 8)" :key="tag">{{ tag }}</span>
                <span v-if="!evidenceTags(decisionItem).length">待补充证据</span>
              </div>
            </aside>

            <div class="audit-decision-form">
              <div class="audit-editable-banner">
                <div>
                  <span>人工决策填写区</span>
                  <b>{{ decisionActionText }}意见会写入审计历史</b>
                </div>
                <em>可编辑</em>
              </div>

              <label>
                <span>审核依据 <em>可编辑</em></span>
                <textarea v-model="decisionForm.basis" :placeholder="decisionGuidance.basis" rows="4" />
              </label>
              <label>
                <span>风险判断 <em>可编辑</em></span>
                <textarea v-model="decisionForm.risk" :placeholder="decisionGuidance.risk" rows="3" />
              </label>
              <label>
                <span>发布影响 <em>可编辑</em></span>
                <textarea v-model="decisionForm.impact" :placeholder="decisionGuidance.impact" rows="3" />
              </label>
              <label v-if="decisionAction === 'MODIFY'" class="full">
                <span>修订内容 <em>可编辑并回写</em></span>
                <textarea v-model="decisionForm.patchNote" placeholder="写明修订后的岗位定义、能力解释或证据口径，系统会随审核记录一并提交。" rows="4" />
              </label>

              <div class="audit-decision-controls">
                <label>
                  <span>发布范围 <em>可编辑</em></span>
                  <select v-model="decisionForm.publishScope">
                    <option value="INTERNAL">内部使用</option>
                    <option value="PRODUCTION">生产发布</option>
                    <option value="REVIEW_ONLY">仅留档复核</option>
                    <option value="BLOCKED">阻断发布</option>
                  </select>
                </label>
                <label>
                  <span>审核人 <em>可编辑</em></span>
                  <input v-model="decisionForm.reviewer" placeholder="请输入审核人或审核小组" />
                </label>
                <label>
                  <span>复核截止 <em>可选</em></span>
                  <input v-model="decisionForm.dueDate" type="date" />
                </label>
                <label class="audit-follow-toggle">
                  <input v-model="decisionForm.followUp" type="checkbox" />
                  <span>需要后续跟踪复核</span>
                </label>
              </div>
            </div>
          </div>

          <footer class="audit-decision-foot">
            <span>{{ decisionAction === 'REJECT' ? '驳回后不会进入发布链路' : '提交后将写入审计历史并更新目标状态' }}</span>
            <div>
              <button class="button secondary" type="button" @click="closeDecision">取消</button>
              <button class="button primary" type="button" :disabled="decisionSubmitting" @click="submitDecision">
                <AppIcon name="audit" :size="15" />{{ decisionSubmitting ? '提交中' : `确认${decisionActionText}` }}
              </button>
            </div>
          </footer>
        </section>
      </div>
    </teleport>
  </div>
</template>
