<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import RichAnswer from '@/components/RichAnswer.vue'

type ChatMessage = {
  role: 'assistant' | 'user'
  text: string
  agents?: string[]
  evidence?: Record<string, any>[]
  actions?: string[]
  confidence?: number
}

type AgentCard = {
  key: string
  name: string
  short: string
  icon: string
  scope: string
  tone: 'blue' | 'mint' | 'amber' | 'rose' | 'slate'
}

const input = ref('')
const busy = ref(false)
const box = ref<HTMLElement>()
const modelStatus = ref<any>({ enabled: false, model: '', mode: 'LOADING', lastError: '' })
const activeEvidenceGroup = ref('ALL')

const sessionId = (() => {
  const key = 'zhitu-agent-session-id'
  const existing = sessionStorage.getItem(key)
  if (existing) return existing
  const created = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`
  sessionStorage.setItem(key, created)
  return created
})()

const agentCatalog: AgentCard[] = [
  { key: 'qa', name: '问答编排智能体', short: '编排', icon: 'brain', scope: '识别问题意图、组织上下文与证据口径', tone: 'slate' },
  { key: 'data', name: '数据治理智能体', short: '数据', icon: 'database', scope: '读取治理后的 JD、质量快照和结构化字段', tone: 'blue' },
  { key: 'insight', name: '岗位洞察智能体', short: '洞察', icon: 'spark', scope: '回答新岗位、岗位趋势和候选岗位来源', tone: 'amber' },
  { key: 'graph', name: '能力图谱与演化智能体', short: '图谱', icon: 'network', scope: '解释岗位能力关系、技能新增与弱化', tone: 'mint' },
  { key: 'match', name: '画像匹配智能体', short: '匹配', icon: 'match', scope: '定位候选人与岗位之间的匹配诊断', tone: 'blue' },
  { key: 'learn', name: '学习规划智能体', short: '培养', icon: 'route', scope: '把能力缺口转化为阶段性培养建议', tone: 'mint' },
  { key: 'audit', name: '可信审核智能体', short: '审核', icon: 'audit', scope: '标记证据门禁、可追溯性和人工复核动作', tone: 'rose' }
]

const quickQuestions = [
  '当前有哪些高可信的新岗位需要进入审核？',
  'Java 开发工程师最近新增或弱化了哪些技能要求？',
  '张晨匹配 Java 开发工程师的主要短板是什么？',
  '基于当前画像和岗位，应该怎么生成阶段性培养方案？'
]

const messages = ref<ChatMessage[]>([
  {
    role: 'assistant',
    text: '我是智能问答工作台。你可以围绕岗位洞察、能力图谱、画像匹配、学习规划和可信审核提问；系统会先检索业务证据，再调用阿里云百炼兼容模型生成回答，并把参与智能体、证据来源和可复核动作一起展示。',
    agents: ['问答编排智能体', '可信审核智能体'],
    evidence: [],
    actions: ['回答必须绑定系统证据', '模型未配置时自动降级为检索摘要'],
    confidence: 0.72
  }
])

const lastAssistant = computed(() => {
  return [...messages.value].reverse().find(item => item.role === 'assistant') || messages.value[0]
})

const evidenceRows = computed(() => lastAssistant.value?.evidence || [])
const answerActions = computed(() => lastAssistant.value?.actions || [])
const confidencePercent = computed(() => Math.round((lastAssistant.value?.confidence || 0) * 100))
const modelEnabled = computed(() => Boolean(modelStatus.value?.enabled))
const providerLabel = computed(() => modelEnabled.value ? '阿里云百炼已接入' : '待配置 AI_API_KEY')

const modelModeLabel = computed(() => {
  if (modelStatus.value?.mode === 'LOADING') return '连接检查中'
  if (modelEnabled.value) return `${modelStatus.value?.model || 'qwen-plus'} · 证据问答`
  return '检索降级模式'
})

const agentUsageText = computed(() => [
  ...(lastAssistant.value?.agents || []),
  ...(answerActions.value || [])
].join(' '))

const routedAgents = computed(() => agentCatalog.map(agent => {
  const active = isAgentActive(agent)
  return {
    ...agent,
    active,
    status: active ? '已参与' : agent.key === 'audit' ? '证据门禁' : '本次未调用'
  }
}))

const evidenceGroups = computed(() => {
  const groups = new Map<string, any>()
  for (const row of evidenceRows.value) {
    const meta = evidenceMeta(row)
    if (!groups.has(meta.key)) {
      groups.set(meta.key, { ...meta, count: 0, rows: [] })
    }
    const group = groups.get(meta.key)
    group.count += 1
    group.rows.push(row)
  }
  return [
    { key: 'ALL', title: '全部证据', agent: '跨智能体证据池', icon: 'audit', tone: 'slate', count: evidenceRows.value.length, rows: evidenceRows.value },
    ...Array.from(groups.values())
  ]
})

const visibleEvidence = computed(() => {
  if (activeEvidenceGroup.value === 'ALL') return evidenceRows.value
  return evidenceGroups.value.find(group => group.key === activeEvidenceGroup.value)?.rows || []
})

const evidenceCoverage = computed(() => {
  if (!evidenceRows.value.length) return 0
  const typed = evidenceRows.value.filter(row => row.evidenceType || row.source).length
  return Math.round((typed / evidenceRows.value.length) * 100)
})

const routeSummary = computed(() => {
  const active = routedAgents.value.filter(agent => agent.active).length
  return {
    active,
    total: agentCatalog.length,
    evidence: evidenceRows.value.length,
    actions: answerActions.value.length
  }
})

function isAgentActive(agent: AgentCard) {
  const text = agentUsageText.value
  if (agent.key === 'qa') return true
  if (agent.key === 'data') return /数据|治理|数据库|JD|governed/i.test(text) || hasEvidence('governance_snapshot', 'governed_job', 'database_analysis_plan')
  if (agent.key === 'insight') return /岗位洞察|新岗位|候选岗位|emerging/i.test(text) || hasEvidence('emerging_role_analysis')
  if (agent.key === 'graph') return /图谱|演化|技能|能力/i.test(text) || hasEvidence('skill_evolution_analysis', 'role_skill_relation')
  if (agent.key === 'match') return /匹配|画像|简历|人岗/i.test(text) || hasEvidence('matching_report')
  if (agent.key === 'learn') return /学习|培养|路径/i.test(text) || hasEvidence('learning_path')
  if (agent.key === 'audit') return /审核|可信|证据|复核|门禁/i.test(text) || evidenceRows.value.length > 0
  return false
}

function hasEvidence(...types: string[]) {
  return evidenceRows.value.some(row => types.includes(String(row.evidenceType || '')))
}

function evidenceMeta(row: Record<string, any>) {
  const type = String(row.evidenceType || row.evidence_type || '')
  if (type === 'governance_snapshot' || type === 'governed_job' || type === 'database_analysis_plan') {
    return { key: 'data', title: '数据治理证据', agent: '数据治理智能体', icon: 'database', tone: 'blue' }
  }
  if (type === 'emerging_role_analysis' || type === 'automatic_analysis_run') {
    return { key: 'insight', title: '岗位洞察证据', agent: '岗位洞察智能体', icon: 'spark', tone: 'amber' }
  }
  if (type === 'skill_evolution_analysis' || type === 'role_skill_relation' || type === 'graph_role') {
    return { key: 'graph', title: '图谱演化证据', agent: '能力图谱与演化智能体', icon: 'network', tone: 'mint' }
  }
  if (type === 'matching_report') {
    return { key: 'match', title: '画像匹配证据', agent: '画像匹配智能体', icon: 'match', tone: 'blue' }
  }
  if (type === 'learning_path' || type === 'learning_context') {
    return { key: 'learn', title: '培养路径证据', agent: '学习规划智能体', icon: 'route', tone: 'mint' }
  }
  return { key: 'audit', title: '可信审核证据', agent: '可信审核智能体', icon: 'audit', tone: 'rose' }
}

function evidenceTitle(row: Record<string, any>, index: number) {
  return row.candidate_name || row.role_name || row.title_standard || row.skill_name ||
    row.person_name || row.title || row.evidenceType || `证据 ${index + 1}`
}

function evidenceDescription(row: Record<string, any>) {
  return row.definition || row.explanation || row.description_clean || row.objective ||
    row.responsibilities || row.suggestions || row.source || '已纳入本次问答上下文，可展开查看结构化字段。'
}

function evidenceTags(row: Record<string, any>) {
  const values = [
    row.tech_stack,
    row.level_name,
    row.change_type,
    row.status,
    row.source,
    row.skills,
    row.required_skills,
    row.missing_skills
  ].filter(Boolean).flatMap(value => String(value).split(/[、,，]/)).map(item => item.trim()).filter(Boolean)
  return Array.from(new Set(values)).slice(0, 6)
}

function formatValue(value: unknown) {
  if (value == null || value === '') return '-'
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(2)
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

async function scrollBottom() {
  await nextTick()
  box.value?.scrollTo({ top: box.value.scrollHeight, behavior: 'smooth' })
}

async function send(text?: string) {
  const question = (text || input.value).trim()
  if (!question || busy.value) return
  messages.value.push({ role: 'user', text: question })
  input.value = ''
  busy.value = true
  activeEvidenceGroup.value = 'ALL'
  await scrollBottom()
  try {
    const result: any = await api.chat(question, sessionId)
    messages.value.push({
      role: 'assistant',
      text: result.answer || '当前没有生成回答。',
      agents: result.agents || [],
      evidence: result.evidence || [],
      actions: result.suggestedActions || [],
      confidence: result.confidence || 0
    })
  } catch (err: any) {
    messages.value.push({
      role: 'assistant',
      text: `请求暂时没有完成：${err.message || '请稍后重试'}`,
      agents: ['问答编排智能体'],
      actions: ['检查后端服务、百炼 Key 或数据源连接'],
      confidence: 0.25
    })
  } finally {
    busy.value = false
    await scrollBottom()
  }
}

async function refreshStatus() {
  try {
    modelStatus.value = await api.agentStatus()
  } catch {
    modelStatus.value = { enabled: false, model: '', mode: 'STATUS_UNAVAILABLE', lastError: '状态接口不可用' }
  }
}

onMounted(refreshStatus)
</script>

<template>
  <div class="qa-product-page">
    <section class="qa-command-hero" v-reveal>
      <div class="qa-hero-copy">
        <span class="match-kicker">智能问答智能体</span>
        <h1>企业岗位能力问答工作台</h1>
        <p>面向招聘、岗位治理和培养决策，把多智能体输出统一编排为可追溯问答。回答先检索系统证据，再由阿里云百炼兼容模型生成，证据不足时明确降级。</p>
        <div class="match-command-actions">
          <button class="button secondary" type="button" @click="refreshStatus">
            <AppIcon name="refresh" :size="16" />刷新模型状态
          </button>
          <button class="button primary" type="button" @click="send(quickQuestions[0])">
            <AppIcon name="spark" :size="16" />查看待审核新岗位
          </button>
        </div>
      </div>

      <aside class="qa-model-card">
        <div class="qa-model-head">
          <span>{{ providerLabel }}</span>
          <strong>{{ modelEnabled ? 'ONLINE' : 'KEY' }}</strong>
        </div>
        <div class="qa-model-meter">
          <span>{{ modelModeLabel }}</span>
          <b>{{ confidencePercent }}%</b>
        </div>
        <div class="qa-model-checks">
          <span><AppIcon name="check" :size="13" />后端读取 AI_API_KEY</span>
          <span><AppIcon name="check" :size="13" />DashScope 兼容模式</span>
          <span><AppIcon name="check" :size="13" />证据绑定回答</span>
        </div>
      </aside>
    </section>

    <section class="qa-kpi-strip">
      <article>
        <span>参与智能体</span>
        <strong>{{ routeSummary.active }} / {{ routeSummary.total }}</strong>
        <small>按问题意图动态路由，不同来源独立标记。</small>
      </article>
      <article>
        <span>可复核证据</span>
        <strong>{{ routeSummary.evidence }}</strong>
        <small>回答中的证据编号可对应到下方台账。</small>
      </article>
      <article>
        <span>证据覆盖率</span>
        <strong>{{ evidenceCoverage }}%</strong>
        <small>结构化证据越完整，企业审核越容易落地。</small>
      </article>
      <article>
        <span>后续动作</span>
        <strong>{{ routeSummary.actions }}</strong>
        <small>输出跳转、复核、补充数据或生成方案建议。</small>
      </article>
    </section>

    <section class="qa-workbench qa-workbench-redesign" v-reveal>
      <aside class="qa-left-rail">
        <div class="qa-panel qa-intent-panel">
          <span class="eyebrow">企业提问入口</span>
          <h2>业务指令台</h2>
          <div class="qa-question-list">
            <button v-for="question in quickQuestions" :key="question" type="button" @click="send(question)">
              <span>{{ question }}</span>
              <AppIcon name="chevron" :size="14" />
            </button>
          </div>
        </div>

        <div class="qa-panel qa-route-panel">
          <span class="eyebrow">智能体分区</span>
          <h2>多智能体路由管线</h2>
          <div class="qa-agent-list">
            <article
              v-for="agent in routedAgents"
              :key="agent.key"
              class="qa-agent-card"
              :class="[`tone-${agent.tone}`, { active: agent.active }]"
            >
              <span><AppIcon :name="agent.icon" :size="15" /></span>
              <div>
                <b>{{ agent.name }}</b>
                <small>{{ agent.scope }}</small>
              </div>
              <em>{{ agent.status }}</em>
            </article>
          </div>
        </div>
      </aside>

      <main class="qa-chat-panel">
        <header class="qa-chat-head">
          <div>
            <span class="eyebrow">证据问答</span>
            <h2>AI 决策解释流</h2>
            <p>把岗位库、能力图谱、候选人画像、学习规划与可信审核证据合并成一条可追溯回答。</p>
          </div>
          <span class="status-badge" :class="modelEnabled ? 'good' : 'warn'">{{ modelModeLabel }}</span>
        </header>

        <div ref="box" class="qa-messages">
          <article v-for="(message, index) in messages" :key="index" class="qa-message" :class="message.role">
            <div class="qa-message-avatar">
              <AppIcon :name="message.role === 'user' ? 'user' : 'brain'" :size="17" />
            </div>
            <div class="qa-message-body">
              <div class="qa-message-meta">
                <b>{{ message.role === 'user' ? '提问人' : '职途智配问答' }}</b>
                <span v-if="message.confidence">可信度 {{ Math.round(message.confidence * 100) }}%</span>
              </div>
              <div class="qa-bubble" :class="{ rich: message.role === 'assistant' }">
                <RichAnswer v-if="message.role === 'assistant'" :content="message.text" />
                <span v-else>{{ message.text }}</span>
              </div>
              <div v-if="message.agents?.length || message.actions?.length" class="qa-answer-meta">
                <span v-for="agent in message.agents" :key="agent" class="tag blue">{{ agent }}</span>
                <span v-for="action in message.actions" :key="action" class="tag green">{{ action }}</span>
              </div>
            </div>
          </article>

          <div v-if="messages.length <= 1 && !busy" class="qa-decision-empty">
            <div class="qa-decision-node">
              <span>01</span>
              <b>岗位能力</b>
              <small>JD 治理与标准能力要求</small>
            </div>
            <div class="qa-decision-node">
              <span>02</span>
              <b>候选画像</b>
              <small>技能、项目、经历证据</small>
            </div>
            <div class="qa-decision-node">
              <span>03</span>
              <b>匹配诊断</b>
              <small>短板、风险和建议动作</small>
            </div>
            <div class="qa-decision-node">
              <span>04</span>
              <b>可信审核</b>
              <small>证据门槛与人工复核</small>
            </div>
          </div>

          <article v-if="busy" class="qa-message assistant">
            <div class="qa-message-avatar"><AppIcon name="brain" :size="17" /></div>
            <div class="qa-message-body">
              <div class="qa-message-meta"><b>职途智配问答</b><span>检索中</span></div>
              <div class="qa-bubble qa-typing">
                <i /><i /><i />
                <span>正在检索治理库、图谱、匹配报告与审核记录</span>
              </div>
            </div>
          </article>
        </div>

        <footer class="qa-composer">
          <textarea
            v-model="input"
            rows="3"
            placeholder="输入岗位、技能、候选人、审核或培养方案问题，例如：张晨为什么适合 Java 开发工程师？"
            @keydown.ctrl.enter.prevent="send()"
          />
          <div>
            <button class="button primary" type="button" :disabled="busy || !input.trim()" @click="send()">
              <AppIcon name="send" :size="16" />发送
            </button>
          </div>
        </footer>
      </main>

      <aside class="qa-right-rail">
        <div class="qa-panel qa-evidence-summary">
          <header>
            <div>
              <span class="eyebrow">证据台账</span>
              <h2>证据驾驶舱</h2>
            </div>
            <strong>{{ evidenceRows.length }}</strong>
          </header>
          <div class="qa-evidence-tabs">
            <button
              v-for="group in evidenceGroups"
              :key="group.key"
              type="button"
              :class="{ active: activeEvidenceGroup === group.key }"
              @click="activeEvidenceGroup = group.key"
            >
              <AppIcon :name="group.icon" :size="14" />
              <span>{{ group.title }}</span>
              <b>{{ group.count }}</b>
            </button>
          </div>
        </div>

        <div class="qa-panel qa-evidence-ledger">
          <header>
            <span class="eyebrow">可复核字段</span>
            <h2>引用明细</h2>
          </header>

          <div v-if="visibleEvidence.length" class="qa-evidence-list">
            <article v-for="(row, index) in visibleEvidence.slice(0, 8)" :key="index">
              <div>
                <span>证据 {{ index + 1 }}</span>
                <b>{{ evidenceTitle(row, index) }}</b>
                <p>{{ evidenceDescription(row) }}</p>
              </div>
              <div class="qa-evidence-tags">
                <span v-for="tag in evidenceTags(row)" :key="tag" class="tag">{{ tag }}</span>
              </div>
              <details>
                <summary>查看结构化字段</summary>
                <dl>
                  <template v-for="(value, key) in row" :key="key">
                    <dt>{{ key }}</dt>
                    <dd>{{ formatValue(value) }}</dd>
                  </template>
                </dl>
              </details>
            </article>
          </div>

          <div v-else class="qa-empty-ledger">
            <AppIcon name="audit" :size="24" />
            <b>等待业务证据</b>
            <p>发送问题后，这里会按智能体来源展示可复核证据。</p>
          </div>
        </div>
      </aside>
    </section>
  </div>
</template>
