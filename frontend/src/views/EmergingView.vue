<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import TrustBadge from '@/components/TrustBadge.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import { useEnglishThemeText } from '@/composables/useEnglishThemeText'

const { tx, phrase } = useEnglishThemeText()

const candidates = ref<any[]>([])
const yearOptions = ref<any[]>([])
const targetYear = ref<number | null>(null)
const loading = ref(false)
const loadingYears = ref(false)
const selectedCandidateKey = ref('')

const trainingYear = computed(() => targetYear.value ? targetYear.value - 1 : null)
const visibleCandidates = computed(() => candidates.value.filter(item => isPlausibleRoleName(displayRoleName(item.candidate_name))))
const avgConfidence = computed(() => {
  if (!visibleCandidates.value.length) return 0
  return Math.round(
    visibleCandidates.value.reduce((sum, item) => sum + Number(item.confidence || 0), 0)
      / visibleCandidates.value.length * 100
  )
})
const totalSamples = computed(() => visibleCandidates.value.reduce((sum, item) => sum + Number(item.sample_size || 0), 0))
const rankedCandidates = computed(() =>
  [...visibleCandidates.value].sort((a, b) => candidateOpportunityScore(b) - candidateOpportunityScore(a))
)
const selectedCandidate = computed(() =>
  rankedCandidates.value.find(item => candidateKey(item) === selectedCandidateKey.value)
  || rankedCandidates.value[0]
  || null
)
const reviewReadyCount = computed(() =>
  visibleCandidates.value.filter(item => reviewStatus(item).tone === 'good').length
)
const riskCandidateCount = computed(() =>
  visibleCandidates.value.filter(item => Number(item.hallucination_risk || 0) >= 0.45).length
)
const avgGrowth = computed(() => {
  if (!visibleCandidates.value.length) return 0
  return Math.round(
    visibleCandidates.value.reduce((sum, item) => sum + Number(item.growth_rate || 0), 0)
      / visibleCandidates.value.length * 100
  )
})
const signalCards = computed(() => [
  {
    label: tx('机会池规模', 'Opportunity Pool'),
    value: visibleCandidates.value.length,
    desc: tx(`${targetYear.value || '目标年'} 可评估高潜岗位`, `${targetYear.value || 'Target Year'} high-potential roles to evaluate`),
    icon: 'briefcase'
  },
  {
    label: tx('建议进入审核', 'Recommended for Audit'),
    value: reviewReadyCount.value,
    desc: tx('可信度、样本与风险同时满足阈值', 'Confidence, sample size and risk meet thresholds'),
    icon: 'audit'
  },
  {
    label: tx('平均增长动量', 'Avg. Growth Momentum'),
    value: `${avgGrowth.value}%`,
    desc: tx(`${trainingYear.value || '前一年'} 年市场信号变化`, `Market signal change from ${trainingYear.value || 'the previous year'}`),
    icon: 'pulse'
  },
  {
    label: tx('需风险复核', 'Risk Review Needed'),
    value: riskCandidateCount.value,
    desc: tx('幻觉风险或证据不足的候选岗位', 'Candidates with hallucination risk or weak evidence'),
    icon: 'focus'
  }
])

function candidateKey(candidate: any): string {
  return String(candidate?.id ?? `${candidate?.target_year || ''}-${candidate?.cluster_key || ''}-${candidate?.candidate_name || ''}`)
}

function pctScore(raw: unknown): number {
  return Math.min(100, Math.max(0, Math.round(Number(raw || 0) * 100)))
}

function candidateOpportunityScore(candidate: any): number {
  const confidence = Number(candidate?.confidence || 0)
  const novelty = Number(candidate?.novelty_score || 0)
  const growth = Number(candidate?.growth_rate || 0)
  const risk = Number(candidate?.hallucination_risk || 0)
  return Math.min(99, Math.max(0, Math.round((confidence * 0.36 + novelty * 0.28 + growth * 0.24 + (1 - risk) * 0.12) * 100)))
}

function reviewStatus(candidate: any) {
  const risk = Number(candidate?.hallucination_risk || 0)
  const sampleSize = Number(candidate?.sample_size || 0)
  const score = candidateOpportunityScore(candidate)
  if (risk >= 0.45) return { label: tx('风险复核', 'Risk Review'), tone: 'risk' }
  if (score >= 78 && sampleSize >= 30) return { label: tx('建议审核', 'Audit Ready'), tone: 'good' }
  return { label: tx('继续观察', 'Watchlist'), tone: 'warn' }
}

function primaryScenario(candidate: any): string {
  return phrase(parseList(candidate?.scenarios)[0] || tx('待补充行业应用证据', 'Industry application evidence pending'))
}

function parseList(raw: unknown): string[] {
  if (Array.isArray(raw)) return raw.map(item => String(item).trim()).filter(Boolean)
  const text = String(raw ?? '').trim()
  if (!text) return []
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) return parsed.map(item => String(item).trim()).filter(Boolean)
  } catch {
    // 兼容旧版逗号分隔内容
  }
  return text
    .replace(/[\[\]"]/g, '')
    .split(/[,，;；]/)
    .map(item => item.trim())
    .filter(Boolean)
}

function displayRoleName(raw: unknown): string {
  let value = String(raw ?? '').normalize('NFKC')
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/[（(【\[].{0,30}?[）)】\]]/g, ' ')
    .replace(/急聘|诚聘|高薪|招聘|直招|五险一金|六险一金|七险一金|双休|大小周|包吃住|包食宿|入职|无实习|不限经验|可居家|居家办公|接受应届生|年终奖/gi, ' ')
    .replace(/(?:月薪|年薪|薪资)?\s*\d+(?:\.\d+)?\s*[kKwW千万元]*\s*[-—~至]\s*\d+(?:\.\d+)?\s*[kKwW千万元]*/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  const specialization = value.match(/^(.{2,24}?(?:工程师|架构师|经理|专家|顾问|设计师|分析师|科学家))\s*[-—_]\s*([\u4e00-\u9fa5A-Za-z0-9]{2,12})$/)
  if (specialization) value = `${specialization[1]}（${specialization[2]}方向）`
  return value.replace(/^[,，、;；|丨/\\:：·•\s-]+|[,，、;；|丨/\\:：·•\s-]+$/g, '').trim()
}

function isPlausibleRoleName(value: string): boolean {
  if (value.length < 2 || value.length > 36) return false
  if (/^(?:居家)?(?:客服|电销|审核|标注员|销售|文员|普工|操作工|业务员)(?:专员|人员|岗位)?$/.test(value)) return false
  const parts = value.split(/[,，、/|丨]+/).filter(Boolean)
  const roleParts = parts.filter(part => /(工程师|架构师|经理|专家|顾问|设计师|分析师|科学家|开发|运维|运营|产品|客服|电销|审核|标注员|销售|文员|普工|操作工|业务员)/.test(part)).length
  return !(parts.length > 1 && roleParts > 1)
}

function cleanResponsibility(raw: unknown): string {
  let value = String(raw ?? '').normalize('NFKC').replace(/\s+/g, ' ').trim()
  for (let index = 0; index < 5; index += 1) {
    const previous = value
    value = value
      .replace(/^[\s,，、;；]*(?:第?[0-9一二三四五六七八九十]+(?:[、.．)）:：-]|\s+))+\s*/, '')
    .replace(/^(?:(?:岗位|工作|职位|核心|主要)?职责|职责描述|职位描述|工作内容|核心工作(?:包括)?)\s*[:：]\s*/, '')
      .trim()
    if (value === previous) break
  }
  return value.replace(/^[◆●•·*\-,，、;；:：\s]+/, '').trim()
}

function responsibilityItems(raw: unknown): string[] {
  return [...new Set(parseList(raw)
    .map(cleanResponsibility)
    .filter(item => item.length >= 8)
    .filter(item => !/^(任职要求|岗位要求|任职资格|职位要求|要求|需|须|应聘|具备|持有|年龄|学历|本科|大专|硕士|经验|福利|薪资|待遇|身体|职业健康|无色盲|无色弱)/.test(item))
    .filter(item => !/(证书|作业证|体检|色盲|色弱|薪资|福利|五险|六险|七险|学历|经验要求|任职资格)/.test(item)))]
}

function displayDefinition(candidate: any): string {
  const roleName = displayRoleName(candidate.candidate_name)
  const responsibility = responsibilityItems(candidate.responsibilities)[0] || tx('完成岗位核心方案设计、实施与持续优化', 'design, implement and continuously optimize core role solutions')
  const scenario = parseList(candidate.scenarios)[0] || tx('相关产业数字化与工程应用场景', 'digital industry and engineering application scenarios')
  return tx(
    `基于 ${candidate.training_year} 年治理 JD 的增长信号、后半年动量和多源证据，预测 ${candidate.target_year} 年高潜岗位“${roleName}”。主要面向${scenario}，核心工作聚焦于${responsibility}。`,
    `Based on growth signals, second-half momentum and multi-source evidence from ${candidate.training_year} governed JDs, the system predicts “${phrase(roleName)}” as a high-potential role for ${candidate.target_year}. It mainly serves ${phrase(scenario)} and focuses on ${phrase(responsibility)}.`
  )
}

async function loadYears() {
  loadingYears.value = true
  try {
    yearOptions.value = await api.emergingYears() as unknown as any[]
    if (!targetYear.value && yearOptions.value.length) {
      targetYear.value = Number(yearOptions.value[0].targetYear)
    }
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loadingYears.value = false
  }
}

async function load() {
  if (!targetYear.value) {
    candidates.value = []
    return
  }
  try {
    candidates.value = await api.candidates(targetYear.value) as unknown as any[]
  } catch (err: any) {
    candidates.value = []
    ElMessage.error(err.message)
  }
}

async function discover() {
  if (!targetYear.value) return
  loading.value = true
  try {
    const result: any = await api.discover(targetYear.value)
    await load()
    ElMessage.success(
      `已使用 ${result?.trainingYear ?? trainingYear.value} 年 JD 预测 ${result?.targetYear ?? targetYear.value} 年，生成 ${result?.candidates ?? candidates.value.length} 个高潜候选岗位`
    )
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

watch(targetYear, async () => {
  await load()
})

watch(rankedCandidates, (items) => {
  if (!items.length) {
    selectedCandidateKey.value = ''
    return
  }
  if (!items.some(item => candidateKey(item) === selectedCandidateKey.value)) {
    selectedCandidateKey.value = candidateKey(items[0])
  }
}, { immediate: true })

onMounted(async () => {
  await loadYears()
  await load()
})
</script>

<template>
  <div class="emerging-product-page">
    <section class="emerging-command-hero" v-reveal>
      <div class="emerging-command-copy">
        <span class="panel-kicker">{{ tx('岗位洞察智能体', 'Job Insight Agent') }}</span>
        <h1>{{ tx('岗位机会洞察工作台', 'Role Opportunity Intelligence Workbench') }}</h1>
        <p>{{ tx('基于已治理 JD 的岗位名称、技能、职责和行业场景信号，识别企业可纳入岗位库的新兴岗位机会，并把结果送入可信审核与后续匹配链路。', 'Identify emerging role opportunities from governed JD titles, skills, responsibilities and industry-scenario signals, then route results into trusted audit and downstream matching workflows.') }}</p>
        <div class="emerging-command-actions">
          <label class="field command-year-select">
            <span>{{ tx('目标年度', 'Target Year') }}</span>
            <select v-model.number="targetYear" class="select" :disabled="loadingYears || !yearOptions.length">
              <option v-for="item in yearOptions" :key="item.targetYear" :value="Number(item.targetYear)">
                {{ item.targetYear }} {{ tx('年', '') }} · {{ tx('训练样本', 'Training samples') }} {{ Number(item.trainingRows || 0).toLocaleString() }}
              </option>
            </select>
          </label>
          <button class="button primary" type="button" :disabled="loading || !targetYear" @click="discover">
            <AppIcon name="spark" :size="16" />{{ loading ? tx('生成中', 'Generating') : tx('生成岗位机会池', 'Generate Opportunity Pool') }}
          </button>
          <button class="button secondary" type="button" @click="load">
            <AppIcon name="refresh" :size="16" /> {{ tx('刷新', 'Refresh') }}
          </button>
        </div>
      </div>

      <aside class="emerging-command-console">
        <div class="command-console-head">
          <span>{{ tx('当前分析窗口', 'Current Analysis Window') }}</span>
          <b>{{ trainingYear || '—' }} → {{ targetYear || '—' }}</b>
        </div>
        <div class="command-console-body">
          <div>
            <span>{{ tx('平均可信度', 'Avg. Confidence') }}</span>
            <b>{{ avgConfidence }}%</b>
          </div>
          <div>
            <span>{{ tx('支持样本', 'Supporting Samples') }}</span>
            <b>{{ totalSamples.toLocaleString() }}</b>
          </div>
          <div>
            <span>{{ tx('首位机会', 'Top Opportunity') }}</span>
            <b>{{ selectedCandidate ? candidateOpportunityScore(selectedCandidate) : 0 }}%</b>
          </div>
        </div>
      </aside>
    </section>

    <section class="emerging-insight-strip" v-reveal>
      <article v-for="(card, index) in signalCards" :key="card.label" v-reveal="index * 35">
        <span><AppIcon :name="card.icon" :size="18" /></span>
        <div>
          <small>{{ card.label }}</small>
          <b>{{ card.value }}</b>
          <p>{{ card.desc }}</p>
        </div>
      </article>
    </section>

    <section class="emerging-product-workspace" v-reveal>
      <aside class="enterprise-panel opportunity-pool-panel">
        <header class="enterprise-panel-head compact">
          <div>
            <span class="panel-kicker">{{ tx('机会池', 'Opportunity Pool') }}</span>
            <h2>{{ targetYear || tx('目标年度', 'Target Year') }} {{ tx('高潜岗位', 'High-potential Roles') }}</h2>
          </div>
          <span class="status-badge warn">{{ trainingYear || 't-1' }} → {{ targetYear || 't' }}</span>
        </header>

        <div v-if="rankedCandidates.length" class="opportunity-pool-list">
          <button
            v-for="candidate in rankedCandidates"
            :key="candidateKey(candidate)"
            class="opportunity-pool-item"
            :class="{ active: candidateKey(candidate) === candidateKey(selectedCandidate) }"
            type="button"
            @click="selectedCandidateKey = candidateKey(candidate)"
          >
            <div class="pool-item-top">
              <b>{{ phrase(displayRoleName(candidate.candidate_name)) }}</b>
              <span class="status-badge" :class="reviewStatus(candidate).tone">{{ reviewStatus(candidate).label }}</span>
            </div>
            <p>{{ primaryScenario(candidate) }}</p>
            <div class="pool-item-metrics">
              <span>{{ tx('机会', 'Opportunity') }} <b>{{ candidateOpportunityScore(candidate) }}%</b></span>
              <span>{{ tx('可信', 'Confidence') }} <b>{{ pctScore(candidate.confidence) }}%</b></span>
              <span>{{ tx('样本', 'Samples') }} <b>{{ Number(candidate.sample_size || 0).toLocaleString() }}</b></span>
            </div>
          </button>
        </div>

        <EmptyState
          v-else
          :title="tx('该年度尚未生成岗位机会池', 'No opportunity pool has been generated for this year')"
          :description="targetYear ? tx(`点击“生成岗位机会池”，系统会基于 ${targetYear - 1} 年治理 JD 识别 ${targetYear} 年高潜岗位。`, `Click “Generate Opportunity Pool” to identify high-potential ${targetYear} roles from ${targetYear - 1} governed JDs.`) : tx('当前治理数据中还没有可用年度。', 'No available year exists in the current governed data.')"
        />
      </aside>

      <article class="enterprise-panel opportunity-detail-panel">
        <template v-if="selectedCandidate">
          <header class="opportunity-detail-head">
            <div>
              <span class="panel-kicker">{{ selectedCandidate.cluster_key || 'Emerging role' }}</span>
              <h2>{{ phrase(displayRoleName(selectedCandidate.candidate_name)) }}</h2>
              <p>{{ displayDefinition(selectedCandidate) }}</p>
            </div>
            <RouterLink :to="{ path: '/audit', query: { type: 'EMERGING_ROLE', id: selectedCandidate.id } }" class="button primary">
              <AppIcon name="audit" :size="16" /> {{ tx('进入可信审核', 'Enter Trusted Audit') }}
            </RouterLink>
          </header>

          <div class="opportunity-summary-grid">
            <section class="opportunity-definition-card">
              <span class="definition-label">{{ tx('岗位机会判断', 'Role Opportunity Assessment') }}</span>
              <div class="opportunity-score-number">{{ candidateOpportunityScore(selectedCandidate) }}%</div>
              <p>{{ tx('综合可信度、新颖度、训练年动量和幻觉风险后，给出是否值得进入企业岗位库审核的机会评分。', 'Combines confidence, novelty, training-year momentum and hallucination risk to score whether the role should enter enterprise role-library audit.') }}</p>
              <div class="opportunity-meta-row">
                <span>{{ Number(selectedCandidate.sample_size || 0).toLocaleString() }} {{ tx('条样本', 'samples') }}</span>
                <span>{{ selectedCandidate.source_count || 0 }} {{ tx('个来源', 'sources') }}</span>
                <span>{{ selectedCandidate.training_year }} → {{ selectedCandidate.target_year }}</span>
              </div>
            </section>

            <aside class="opportunity-score-card">
              <TrustBadge :score="selectedCandidate.confidence || 0" />
              <div class="score-line"><span>{{ tx('新颖度', 'Novelty') }}</span><b>{{ pctScore(selectedCandidate.novelty_score) }}%</b></div>
              <ProgressBar :value="pctScore(selectedCandidate.novelty_score)" tone="mint" />
              <div class="score-line"><span>{{ tx('增长动量', 'Growth Momentum') }}</span><b>{{ pctScore(selectedCandidate.growth_rate) }}%</b></div>
              <ProgressBar :value="pctScore(selectedCandidate.growth_rate)" tone="gold" />
              <div class="score-line"><span>{{ tx('幻觉风险', 'Hallucination Risk') }}</span><b>{{ pctScore(selectedCandidate.hallucination_risk) }}%</b></div>
              <ProgressBar :value="pctScore(selectedCandidate.hallucination_risk)" tone="gold" />
            </aside>
          </div>

          <div class="opportunity-module-grid">
            <section class="opportunity-module wide">
              <span class="definition-label">{{ tx('核心职责证据', 'Core Responsibility Evidence') }}</span>
              <div v-if="responsibilityItems(selectedCandidate.responsibilities).length" class="responsibility-list">
                <div v-for="(item, responsibilityIndex) in responsibilityItems(selectedCandidate.responsibilities).slice(0, 4)" :key="item" class="responsibility-item">
                  <span>{{ String(responsibilityIndex + 1).padStart(2, '0') }}</span>
                  <p>{{ phrase(item) }}</p>
                </div>
              </div>
              <p v-else class="muted">{{ tx('暂无足够职责证据，建议进入可信审核补充。', 'Insufficient responsibility evidence. Route to trusted audit for enrichment.') }}</p>
            </section>

            <section class="opportunity-module">
              <span class="definition-label">{{ tx('必备技能', 'Required Skills') }}</span>
              <div class="skill-cloud">
                <span v-for="skill in parseList(selectedCandidate.required_skills).slice(0, 10)" :key="skill" class="tag green">{{ skill }}</span>
                <span v-if="!parseList(selectedCandidate.required_skills).length" class="muted">{{ tx('待补充', 'Pending') }}</span>
              </div>
            </section>

            <section class="opportunity-module">
              <span class="definition-label">{{ tx('加分技能', 'Preferred Skills') }}</span>
              <div class="skill-cloud">
                <span v-for="skill in parseList(selectedCandidate.bonus_skills).slice(0, 10)" :key="skill" class="tag gold-tag">{{ skill }}</span>
                <span v-if="!parseList(selectedCandidate.bonus_skills).length" class="muted">{{ tx('暂无独立加分技能证据', 'No independent preferred-skill evidence yet') }}</span>
              </div>
            </section>

            <section class="opportunity-module wide">
              <span class="definition-label">{{ tx('行业应用场景', 'Industry Scenarios') }}</span>
              <div class="scenario-list">
                <span v-for="scenario in parseList(selectedCandidate.scenarios).slice(0, 6)" :key="scenario">
                  <AppIcon name="focus" :size="14" />{{ phrase(scenario) }}
                </span>
                <span v-if="!parseList(selectedCandidate.scenarios).length" class="muted">{{ tx('待补充行业应用证据', 'Industry application evidence pending') }}</span>
              </div>
            </section>
          </div>

          <div class="opportunity-delivery-strip">
            <div>
              <b>{{ tx('审核入库', 'Audit into Library') }}</b>
              <span>{{ tx('确认岗位名称、职责边界与能力要求', 'Confirm role name, responsibility boundary and capability requirements') }}</span>
            </div>
            <div>
              <b>{{ tx('能力图谱更新', 'Capability Graph Update') }}</b>
              <span>{{ tx('沉淀岗位到技能、场景、证据关系', 'Persist role-skill-scenario-evidence relations') }}</span>
            </div>
            <div>
              <b>{{ tx('招聘策略联动', 'Recruiting Strategy Linkage') }}</b>
              <span>{{ tx('进入人岗匹配、学习路径和人才画像', 'Connect to matching, learning paths and talent profiles') }}</span>
            </div>
          </div>
        </template>

        <EmptyState
          v-else
          :title="tx('暂无可查看的岗位机会', 'No role opportunity to display')"
          :description="tx('选择目标年度并生成岗位机会池后，系统会在这里展示可审核的新兴岗位。', 'Select a target year and generate an opportunity pool to review emerging roles here.')"
        />
      </article>
    </section>
  </div>
</template>
