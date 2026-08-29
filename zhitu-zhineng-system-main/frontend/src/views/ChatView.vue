<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import RichAnswer from '@/components/RichAnswer.vue'
import { useEnglishThemeText } from '@/composables/useEnglishThemeText'

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
const { tx, phrase } = useEnglishThemeText()

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

const agentCatalog = computed<AgentCard[]>(() => [
  { key: 'qa', name: tx('问答编排智能体', 'Q&A Orchestration Agent'), short: tx('编排', 'Route'), icon: 'brain', scope: tx('识别问题意图、组织上下文与证据口径', 'Detect intent, organize context and evidence policy'), tone: 'slate' },
  { key: 'data', name: tx('数据治理智能体', 'Data Governance Agent'), short: tx('数据', 'Data'), icon: 'database', scope: tx('读取治理后的 JD、质量快照和结构化字段', 'Read governed JDs, quality snapshots and structured fields'), tone: 'blue' },
  { key: 'insight', name: tx('岗位洞察智能体', 'Job Insight Agent'), short: tx('洞察', 'Insight'), icon: 'spark', scope: tx('回答新岗位、岗位趋势和候选岗位来源', 'Answer emerging-role, trend and source questions'), tone: 'amber' },
  { key: 'graph', name: tx('能力图谱与演化智能体', 'Capability Graph & Evolution Agent'), short: tx('图谱', 'Graph'), icon: 'network', scope: tx('解释岗位能力关系、技能新增与弱化', 'Explain role-capability relations and skill evolution'), tone: 'mint' },
  { key: 'match', name: tx('画像匹配智能体', 'Talent Matching Agent'), short: tx('匹配', 'Match'), icon: 'match', scope: tx('定位候选人与岗位之间的匹配诊断', 'Diagnose candidate-role matching results'), tone: 'blue' },
  { key: 'learn', name: tx('学习规划智能体', 'Learning Path Agent'), short: tx('培养', 'Learn'), icon: 'route', scope: tx('把能力缺口转化为阶段性培养建议', 'Turn capability gaps into staged development plans'), tone: 'mint' },
  { key: 'audit', name: tx('可信审核智能体', 'Trust Audit Agent'), short: tx('审核', 'Audit'), icon: 'audit', scope: tx('标记证据门禁、可追溯性和人工复核动作', 'Mark evidence gates, traceability and human review actions'), tone: 'rose' }
])

const quickQuestions = computed(() => [
  tx('当前有哪些高可信的新岗位需要进入审核？', 'Which high-confidence emerging roles should enter audit review?'),
  tx('Java 开发工程师最近新增或弱化了哪些技能要求？', 'Which Java developer skill requirements have emerged or weakened recently?'),
  tx('张晨匹配 Java 开发工程师的主要短板是什么？', 'What are Zhang Chen’s major gaps for the Java Developer role?'),
  tx('基于当前画像和岗位，应该怎么生成阶段性培养方案？', 'How should a staged development plan be generated from the current profile and role?')
])

const messages = ref<ChatMessage[]>([
  {
    role: 'assistant',
    text: tx('我是智能问答工作台。你可以围绕岗位洞察、能力图谱、画像匹配、学习规划和可信审核提问；系统会先检索业务证据，再调用大模型生成回答，并把参与智能体、证据来源和可复核动作一起展示。', 'I am the AI Q&A workbench. Ask about role insights, capability graphs, talent matching, learning plans and trusted audit. The system retrieves business evidence first, then generates an auditable LLM answer.'),
    agents: [tx('问答编排智能体', 'Q&A Orchestration Agent'), tx('可信审核智能体', 'Trust Audit Agent')],
    evidence: [],
    actions: [tx('回答必须绑定系统证据', 'Answers must bind to system evidence'), tx('模型未配置时自动降级为检索摘要', 'Falls back to retrieval summary when the model is unavailable')],
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
const providerLabel = computed(() => {
  if (!modelEnabled.value) return tx('待配置 AI_API_KEY', 'AI_API_KEY Required')
  const model = String(modelStatus.value?.model || '').toLowerCase()
  if (model.startsWith('deepseek')) return tx('DeepSeek 主模型已接入', 'DeepSeek Primary Model Connected')
  if (model.startsWith('qwen')) return tx('阿里云百炼已接入', 'Alibaba DashScope Connected')
  return tx('OpenAI 兼容模型已接入', 'OpenAI-compatible Model Connected')
})

const modelModeLabel = computed(() => {
  if (modelStatus.value?.mode === 'LOADING') return tx('连接检查中', 'Checking connection')
  if (modelEnabled.value) return `${modelStatus.value?.model || 'qwen-plus'} · ${tx('证据问答', 'Evidence Q&A')}`
  return tx('检索降级模式', 'Retrieval fallback mode')
})

const agentUsageText = computed(() => [
  ...(lastAssistant.value?.agents || []),
  ...(answerActions.value || [])
].join(' '))

const routedAgents = computed(() => agentCatalog.value.map(agent => {
  const active = isAgentActive(agent)
  return {
    ...agent,
    active,
    status: active ? tx('已参与', 'Active') : agent.key === 'audit' ? tx('证据门禁', 'Evidence Gate') : tx('本次未调用', 'Standby')
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
    { key: 'ALL', title: tx('全部证据', 'All Evidence'), agent: tx('跨智能体证据池', 'Cross-agent Evidence Pool'), icon: 'audit', tone: 'slate', count: evidenceRows.value.length, rows: evidenceRows.value },
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
    total: agentCatalog.value.length,
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
    return { key: 'data', title: tx('数据治理证据', 'Data Governance Evidence'), agent: tx('数据治理智能体', 'Data Governance Agent'), icon: 'database', tone: 'blue' }
  }
  if (type === 'emerging_role_analysis' || type === 'automatic_analysis_run') {
    return { key: 'insight', title: tx('岗位洞察证据', 'Job Insight Evidence'), agent: tx('岗位洞察智能体', 'Job Insight Agent'), icon: 'spark', tone: 'amber' }
  }
  if (type === 'skill_evolution_analysis' || type === 'role_skill_relation' || type === 'graph_role') {
    return { key: 'graph', title: tx('图谱演化证据', 'Graph Evolution Evidence'), agent: tx('能力图谱与演化智能体', 'Capability Graph & Evolution Agent'), icon: 'network', tone: 'mint' }
  }
  if (type === 'matching_report') {
    return { key: 'match', title: tx('画像匹配证据', 'Talent Matching Evidence'), agent: tx('画像匹配智能体', 'Talent Matching Agent'), icon: 'match', tone: 'blue' }
  }
  if (type === 'learning_path' || type === 'learning_context') {
    return { key: 'learn', title: tx('培养路径证据', 'Learning Path Evidence'), agent: tx('学习规划智能体', 'Learning Path Agent'), icon: 'route', tone: 'mint' }
  }
  return { key: 'audit', title: tx('可信审核证据', 'Trust Audit Evidence'), agent: tx('可信审核智能体', 'Trust Audit Agent'), icon: 'audit', tone: 'rose' }
}

function evidenceTitle(row: Record<string, any>, index: number) {
  return row.candidate_name || row.role_name || row.title_standard || row.skill_name ||
    row.person_name || row.title || row.evidenceType || `${tx('证据', 'Evidence')} ${index + 1}`
}

function evidenceDescription(row: Record<string, any>) {
  return row.definition || row.explanation || row.description_clean || row.objective ||
    row.responsibilities || row.suggestions || row.source || tx('已纳入本次问答上下文，可展开查看结构化字段。', 'Included in the current Q&A context. Expand to inspect structured fields.')
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
  return phrase(value)
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
      text: result.answer || tx('当前没有生成回答。', 'No answer was generated.'),
      agents: (result.agents || []).map((item: unknown) => phrase(item)),
      evidence: result.evidence || [],
      actions: result.suggestedActions || [],
      confidence: result.confidence || 0
    })
  } catch (err: any) {
    messages.value.push({
      role: 'assistant',
      text: `${tx('请求暂时没有完成：', 'The request could not be completed: ')}${err.message || tx('请稍后重试', 'please try again later')}`,
      agents: [tx('问答编排智能体', 'Q&A Orchestration Agent')],
      actions: [tx('检查后端服务、模型 Key 或数据源连接', 'Check backend service, model key or data source connection')],
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
    modelStatus.value = { enabled: false, model: '', mode: 'STATUS_UNAVAILABLE', lastError: tx('状态接口不可用', 'Status API unavailable') }
  }
}

onMounted(refreshStatus)
</script>

<template>
  <div class="qa-product-page">
    <section class="qa-command-hero" v-reveal>
      <div class="qa-hero-copy">
        <span class="match-kicker">{{ tx('智能问答智能体', 'AI Q&A Agent') }}</span>
        <h1>{{ tx('企业岗位能力问答工作台', 'Enterprise Job-Capability Q&A Workbench') }}</h1>
        <p>{{ tx('面向招聘、岗位治理和培养决策，把多智能体输出统一编排为可追溯问答。回答先检索系统证据，再由大模型生成，证据不足时明确降级。', 'For recruiting, job governance and talent development decisions, orchestrate multi-agent outputs into traceable answers. The system retrieves evidence first, then generates an LLM answer with clear fallback when evidence is insufficient.') }}</p>
        <div class="match-command-actions">
          <button class="button secondary" type="button" @click="refreshStatus">
            <AppIcon name="refresh" :size="16" />{{ tx('刷新模型状态', 'Refresh Model Status') }}
          </button>
          <button class="button primary" type="button" @click="send(quickQuestions[0])">
            <AppIcon name="spark" :size="16" />{{ tx('查看待审核新岗位', 'View Roles for Audit') }}
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
          <span><AppIcon name="check" :size="13" />{{ tx('后端读取 AI_API_KEY', 'Backend reads AI API key') }}</span>
          <span><AppIcon name="check" :size="13" />{{ tx('OpenAI 兼容模式', 'OpenAI-compatible mode') }}</span>
          <span><AppIcon name="check" :size="13" />{{ tx('证据绑定回答', 'Evidence-grounded answer') }}</span>
        </div>
      </aside>
    </section>

    <section class="qa-kpi-strip">
      <article>
        <span>{{ tx('参与智能体', 'Participating Agents') }}</span>
        <strong>{{ routeSummary.active }} / {{ routeSummary.total }}</strong>
        <small>{{ tx('按问题意图动态路由，不同来源独立标记。', 'Dynamic routing by question intent with source-level labeling.') }}</small>
      </article>
      <article>
        <span>{{ tx('可复核证据', 'Reviewable Evidence') }}</span>
        <strong>{{ routeSummary.evidence }}</strong>
        <small>{{ tx('回答中的证据编号可对应到下方台账。', 'Evidence IDs in answers map to the ledger below.') }}</small>
      </article>
      <article>
        <span>{{ tx('证据覆盖率', 'Evidence Coverage') }}</span>
        <strong>{{ evidenceCoverage }}%</strong>
        <small>{{ tx('结构化证据越完整，企业审核越容易落地。', 'More complete structured evidence makes enterprise audit easier.') }}</small>
      </article>
      <article>
        <span>{{ tx('后续动作', 'Next Actions') }}</span>
        <strong>{{ routeSummary.actions }}</strong>
        <small>{{ tx('输出跳转、复核、补充数据或生成方案建议。', 'Suggest navigation, review, data enrichment or plan generation.') }}</small>
      </article>
    </section>

    <section class="qa-workbench qa-workbench-redesign" v-reveal>
      <aside class="qa-left-rail">
        <div class="qa-panel qa-intent-panel">
          <span class="eyebrow">{{ tx('企业提问入口', 'Enterprise Query Entry') }}</span>
          <h2>{{ tx('业务指令台', 'Business Command Console') }}</h2>
          <div class="qa-question-list">
            <button v-for="question in quickQuestions" :key="question" type="button" @click="send(question)">
              <span>{{ question }}</span>
              <AppIcon name="chevron" :size="14" />
            </button>
          </div>
        </div>

        <div class="qa-panel qa-route-panel">
          <span class="eyebrow">{{ tx('智能体分区', 'Agent Zones') }}</span>
          <h2>{{ tx('多智能体路由管线', 'Multi-agent Routing Pipeline') }}</h2>
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
            <span class="eyebrow">{{ tx('证据问答', 'Evidence Q&A') }}</span>
            <h2>{{ tx('AI 决策解释流', 'AI Decision Explanation Stream') }}</h2>
            <p>{{ tx('把岗位库、能力图谱、候选人画像、学习规划与可信审核证据合并成一条可追溯回答。', 'Merge role libraries, capability graphs, talent profiles, learning plans and audit evidence into one traceable answer.') }}</p>
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
                <b>{{ message.role === 'user' ? tx('提问人', 'User') : tx('职途智配问答', 'Zhitu Q&A') }}</b>
                <span v-if="message.confidence">{{ tx('可信度', 'Confidence') }} {{ Math.round(message.confidence * 100) }}%</span>
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
              <b>{{ tx('岗位能力', 'Role Capabilities') }}</b>
              <small>{{ tx('JD 治理与标准能力要求', 'JD governance and standard requirements') }}</small>
            </div>
            <div class="qa-decision-node">
              <span>02</span>
              <b>{{ tx('候选画像', 'Talent Profile') }}</b>
              <small>{{ tx('技能、项目、经历证据', 'Skills, projects and experience evidence') }}</small>
            </div>
            <div class="qa-decision-node">
              <span>03</span>
              <b>{{ tx('匹配诊断', 'Match Diagnosis') }}</b>
              <small>{{ tx('短板、风险和建议动作', 'Gaps, risks and recommended actions') }}</small>
            </div>
            <div class="qa-decision-node">
              <span>04</span>
              <b>{{ tx('可信审核', 'Trusted Audit') }}</b>
              <small>{{ tx('证据门槛与人工复核', 'Evidence gate and human review') }}</small>
            </div>
          </div>

          <article v-if="busy" class="qa-message assistant">
            <div class="qa-message-avatar"><AppIcon name="brain" :size="17" /></div>
            <div class="qa-message-body">
              <div class="qa-message-meta"><b>{{ tx('职途智配问答', 'Zhitu Q&A') }}</b><span>{{ tx('检索中', 'Retrieving') }}</span></div>
              <div class="qa-bubble qa-typing">
                <i /><i /><i />
                <span>{{ tx('正在检索治理库、图谱、匹配报告与审核记录', 'Retrieving governance library, graph, matching reports and audit records') }}</span>
              </div>
            </div>
          </article>
        </div>

        <footer class="qa-composer">
          <textarea
            v-model="input"
            rows="3"
            :placeholder="tx('输入岗位、技能、候选人、审核或培养方案问题，例如：张晨为什么适合 Java 开发工程师？', 'Ask about roles, skills, candidates, audit or development plans, e.g. why is Zhang Chen suitable for Java Developer?')"
            @keydown.ctrl.enter.prevent="send()"
          />
          <div>
            <button class="button primary" type="button" :disabled="busy || !input.trim()" @click="send()">
              <AppIcon name="send" :size="16" />{{ tx('发送', 'Send') }}
            </button>
          </div>
        </footer>
      </main>

      <aside class="qa-right-rail">
        <div class="qa-panel qa-evidence-summary">
          <header>
            <div>
              <span class="eyebrow">{{ tx('证据台账', 'Evidence Ledger') }}</span>
              <h2>{{ tx('证据驾驶舱', 'Evidence Cockpit') }}</h2>
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
            <span class="eyebrow">{{ tx('可复核字段', 'Reviewable Fields') }}</span>
            <h2>{{ tx('引用明细', 'Citation Details') }}</h2>
          </header>

          <div v-if="visibleEvidence.length" class="qa-evidence-list">
            <article v-for="(row, index) in visibleEvidence.slice(0, 8)" :key="index">
              <div>
                <span>{{ tx('证据', 'Evidence') }} {{ index + 1 }}</span>
                <b>{{ phrase(evidenceTitle(row, index)) }}</b>
                <p>{{ phrase(evidenceDescription(row)) }}</p>
              </div>
              <div class="qa-evidence-tags">
                <span v-for="tag in evidenceTags(row)" :key="tag" class="tag">{{ phrase(tag) }}</span>
              </div>
              <details>
                <summary>{{ tx('查看结构化字段', 'View structured fields') }}</summary>
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
            <b>{{ tx('等待业务证据', 'Waiting for business evidence') }}</b>
            <p>{{ tx('发送问题后，这里会按智能体来源展示可复核证据。', 'After you send a question, reviewable evidence will appear here by agent source.') }}</p>
          </div>
        </div>
      </aside>
    </section>
  </div>
</template>
