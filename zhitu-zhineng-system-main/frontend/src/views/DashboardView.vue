<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api'
import EChart from '@/components/EChart.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import AppIcon from '@/components/AppIcon.vue'

const data = ref<any>(null)
const loading = ref(true)
const running = ref(false)
const error = ref('')
const pipelineNotice = ref('')
const { t } = useI18n()

const sixAgents = [
  { no: '01', name: '数据治理智能体', icon: 'database', role: '采集 · 清洗 · 去重 · 质量分级', to: '/parsing' },
  { no: '02', name: '岗位洞察智能体', icon: 'spark', role: '新岗位发现 · 趋势识别', to: '/emerging' },
  { no: '03', name: '能力图谱与演化智能体', icon: 'network', role: '能力图谱 · 技能演化', to: '/graph' },
  { no: '04', name: '画像匹配智能体', icon: 'match', role: '简历解析 · 多维匹配', to: '/matching' },
  { no: '05', name: '学习规划智能体', icon: 'route', role: '技能缺口 · 成长路径', to: '/learning' },
  { no: '06', name: '可信审核智能体', icon: 'audit', role: '证据绑定 · 幻觉防控', to: '/audit' }
]

const executionStages = [
  { no: 'A1', title: '数据资产入仓', metric: '岗位库可用', desc: '多源 JD、行业资料和岗位标准完成治理，形成可进入图谱与匹配的岗位资产。', deliverable: '治理岗位 · 质量分级 · 来源证据' },
  { no: 'A2', title: '能力结构建模', metric: '关系可解释', desc: '将岗位要求沉淀为岗位画像、技能关系和演化事件，支撑后续诊断。', deliverable: '岗位画像 · 能力图谱 · 演化记录' },
  { no: 'A3', title: '人岗决策输出', metric: '结论可复核', desc: '把候选人画像与岗位能力要求对齐，输出匹配分数、缺口和面试核验点。', deliverable: '匹配报告 · 缺口清单 · 面试建议' },
  { no: 'A4', title: '审核发布闭环', metric: '风险可控', desc: '高影响结论进入可信审核，按智能体来源保留证据台账和复盘记录。', deliverable: '审核队列 · 证据台账 · 结果复盘' }
]

const heroSignals = [
  { label: '数据资产', value: '治理入仓', icon: 'database' },
  { label: '岗位能力', value: '图谱沉淀', icon: 'network' },
  { label: '招聘决策', value: '证据解释', icon: 'match' }
]

const capabilityPillars = [
  { title: '岗位数据治理底座', outcome: '产出：可信岗位库', desc: '多源 JD 接入、去重清洗、岗位标准化和质量分级，形成可复用岗位资产。', icon: 'database', tone: 'blue' },
  { title: '岗位机会洞察', outcome: '产出：岗位机会池', desc: '识别新兴岗位、趋势信号与候选岗位来源，支撑企业岗位库持续扩展。', icon: 'spark', tone: 'amber' },
  { title: '能力图谱与演化', outcome: '产出：能力关系网络', desc: '沉淀岗位—技能—证据关系，并监控技能新增、弱化与要求变化。', icon: 'network', tone: 'green' },
  { title: '候选人画像匹配', outcome: '产出：匹配诊断报告', desc: '解析简历字段、项目和经历证据，输出可解释的人岗匹配结论。', icon: 'match', tone: 'blue' },
  { title: '阶段化培养路径', outcome: '产出：岗位成长方案', desc: '把能力缺口转为分层任务、项目交付物和可验收的培养阶段。', icon: 'route', tone: 'green' },
  { title: '可信审核与发布', outcome: '产出：审核证据台账', desc: '按智能体来源分流审核，证据不足、幻觉风险和高影响结论进入人工放行。', icon: 'audit', tone: 'rose' }
]

async function load(refresh = false) {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.dashboard(refresh)
    if (!refresh) pipelineNotice.value = ''
  } catch (err: any) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function run() {
  running.value = true
  error.value = ''
  pipelineNotice.value = ''
  try {
    const result: any = await api.runPipeline()
    pipelineNotice.value = result?.status === 'DEGRADED'
      ? (result?.message || '流水线已降级执行，当前展示可用治理快照。')
      : '完整流水线已执行完成，当前总览已更新。'
    await load(false)
  } catch (err: any) {
    try {
      data.value = await api.dashboard(false)
      pipelineNotice.value = `流水线执行未完成，已展示最近可用总览快照。原因：${err.message}`
      error.value = ''
    } catch (fallbackErr: any) {
      error.value = fallbackErr?.message || err.message
    }
  } finally {
    running.value = false
  }
}

onMounted(() => load(false))

const maxStack = computed(() => Math.max(1, ...(data.value?.stacks || []).map((item: any) => Number(item.value || 0))))
const topStacks = computed(() => (data.value?.stacks || []).slice(0, 8))
const stackTotal = computed(() => (data.value?.stacks || []).reduce((sum: number, item: any) => sum + Number(item.value || 0), 0))
const trustScore = computed(() => Math.round((data.value?.quality?.avg_quality || 0) * 100))
const sourceLabel = computed(() => data.value?.dataSource === 'MYSQL_GOVERNED_MILLION_JD' ? '百万治理数据' : '演示数据')
const trustChecks = [
  '岗位数据可追溯',
  '技能证据已绑定',
  '审核事件可回看'
]

const executiveKpis = computed(() => [
  { label: t('dashboard.governedJobs'), value: data.value?.metrics?.jobs ?? 0, desc: '进入治理与分析层的岗位样本', icon: 'database', tone: 'blue' },
  { label: t('dashboard.skillRelations'), value: data.value?.metrics?.relations ?? 0, desc: '岗位—技能证据关系边', icon: 'network', tone: 'green' },
  { label: t('dashboard.rolesCandidates'), value: `${data.value?.metrics?.roles ?? 0} / ${data.value?.metrics?.emerging ?? 0}`, desc: '标准岗位与新岗位候选', icon: 'spark', tone: 'amber' },
  { label: t('dashboard.matchReports'), value: data.value?.metrics?.matches ?? 0, desc: '可解释人岗匹配报告', icon: 'match', tone: 'rose' }
])

const trendOption = computed(() => ({
  animationDuration: 900,
  animationEasing: 'cubicOut',
  tooltip: { trigger: 'axis', backgroundColor: '#0f172a', borderWidth: 0, textStyle: { color: '#fff' } },
  grid: { left: 48, right: 24, top: 26, bottom: 34 },
  xAxis: {
    type: 'category',
    data: (data.value?.trend || []).map((item: any) => item.day),
    axisLabel: { color: '#7a8494', fontSize: 10 },
    axisLine: { lineStyle: { color: '#dbe3ee' } },
    axisTick: { show: false }
  },
  yAxis: {
    type: 'value',
    axisLabel: { color: '#7a8494', fontSize: 10 },
    splitLine: { lineStyle: { color: '#edf2f7' } }
  },
  series: [{
    type: 'line',
    smooth: 0.42,
    data: (data.value?.trend || []).map((item: any) => item.value),
    showSymbol: true,
    symbolSize: 8,
    lineStyle: { width: 3, color: '#0f766e' },
    itemStyle: { color: '#0f766e', borderColor: '#fff', borderWidth: 2 },
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [
          { offset: 0, color: 'rgba(15,118,110,.18)' },
          { offset: 1, color: 'rgba(15,118,110,0)' }
        ]
      }
    }
  }]
}))

const skillOption = computed(() => ({
  animationDuration: 900,
  animationDelay: (index: number) => index * 55,
  tooltip: { trigger: 'item', backgroundColor: '#0f172a', borderWidth: 0, textStyle: { color: '#fff' } },
  grid: { left: 104, right: 42, top: 18, bottom: 18 },
  xAxis: {
    type: 'value',
    axisLabel: { color: '#7a8494' },
    splitLine: { lineStyle: { color: '#edf2f7' } }
  },
  yAxis: {
    type: 'category',
    inverse: true,
    data: (data.value?.topSkills || []).slice(0, 8).map((item: any) => item.name),
    axisLabel: { color: '#334155', fontSize: 11, fontWeight: 700 },
    axisLine: { show: false },
    axisTick: { show: false }
  },
  series: [{
    type: 'bar',
    data: (data.value?.topSkills || []).slice(0, 8).map((item: any) => item.value),
    barWidth: 12,
    label: { show: true, position: 'right', color: '#64748b', fontSize: 10 },
    itemStyle: {
      borderRadius: [0, 6, 6, 0],
      color: {
        type: 'linear', x: 0, y: 0, x2: 1, y2: 0,
        colorStops: [
          { offset: 0, color: '#1d4ed8' },
          { offset: 1, color: '#0f766e' }
        ]
      }
    }
  }]
}))
</script>

<template>
  <div>
    <section class="dashboard-hero enterprise-hero" v-reveal>
      <div class="enterprise-hero-main">
        <div class="hero-title-block">
          <span class="dashboard-hero-eyebrow">{{ t('dashboard.eyebrow') }}</span>
          <h1>{{ t('dashboard.title') }}</h1>
          <p>{{ t('dashboard.subtitle') }}</p>
        </div>

        <div class="hero-action-row">
          <button class="button light-primary" type="button" :disabled="running" @click="run">
            <AppIcon name="play" :size="15" /> {{ running ? t('common.running') : t('common.run') }}
          </button>
          <button class="button hero-ghost" type="button" @click="load(true)">
            <AppIcon name="refresh" :size="15" /> {{ t('common.refresh') }}
          </button>
        </div>
      </div>

      <aside class="enterprise-hero-aside" aria-label="系统价值概览">
        <div class="hero-aside-head">
          <span>系统能力总览</span>
          <b>Talent Intelligence Console</b>
        </div>
        <div class="hero-signal-list enterprise-signals">
          <div v-for="item in heroSignals" :key="item.label" class="hero-signal">
            <span><AppIcon :name="item.icon" :size="16" /></span>
            <div>
              <b>{{ item.value }}</b>
              <small>{{ item.label }}</small>
            </div>
          </div>
        </div>
        <div class="hero-aside-summary">
          <span>核心闭环</span>
          <b>数据治理 → 能力图谱 → 匹配诊断 → 成长建议</b>
        </div>
      </aside>
    </section>

    <div v-if="error" class="inline-alert error"><b>系统处理失败</b><span>{{ error }}</span></div>
    <div v-else-if="pipelineNotice" class="inline-alert success"><b>流水线状态</b><span>{{ pipelineNotice }}</span></div>

    <div v-if="loading" class="loading-layout" aria-label="正在加载">
      <div v-for="index in 6" :key="index" class="skeleton-block" />
    </div>

    <template v-else-if="data">
      <section class="executive-kpi-strip" v-reveal>
        <article v-for="(item, index) in executiveKpis" :key="item.label" class="executive-kpi-card" :class="`tone-${item.tone}`" v-reveal="index * 35">
          <span class="kpi-icon"><AppIcon :name="item.icon" :size="18" /></span>
          <div>
            <span>{{ item.label }}</span>
            <b>{{ typeof item.value === 'number' ? Number(item.value).toLocaleString() : item.value }}</b>
            <small>{{ item.desc }}</small>
          </div>
        </article>
      </section>

      <section class="solution-layout" v-reveal>
        <article class="enterprise-panel solution-capability-panel">
          <header class="enterprise-panel-head compact">
            <div>
              <span class="panel-kicker">解决方案能力</span>
              <h2>六类产品能力落地</h2>
              <p>围绕企业招聘、人岗匹配与培养闭环，把六个智能体沉淀为可交付能力。</p>
            </div>
            <span class="source-chip light">Recruiting OS</span>
          </header>
          <div class="solution-capability-list">
            <div v-for="(pillar, index) in capabilityPillars" :key="pillar.title" class="solution-capability-row" :class="`tone-${pillar.tone}`" v-reveal="index * 45">
              <i>{{ String(index + 1).padStart(2, '0') }}</i>
              <span><AppIcon :name="pillar.icon" :size="18" /></span>
              <div>
                <b>{{ pillar.title }}</b>
                <strong>{{ pillar.outcome }}</strong>
                <p>{{ pillar.desc }}</p>
              </div>
            </div>
          </div>
          <div class="solution-delivery-strip">
            <div>
              <span>01 输入</span>
              <b>岗位 JD / 简历 / 行业资料</b>
            </div>
            <div>
              <span>02 建模</span>
              <b>岗位画像 / 能力图谱 / 候选人画像</b>
            </div>
            <div>
              <span>03 决策</span>
              <b>匹配诊断 / 培养路径 / 面试核验</b>
            </div>
            <div>
              <span>04 治理</span>
              <b>证据台账 / 审核放行 / 结果复盘</b>
            </div>
          </div>
        </article>

      </section>

      <section class="enterprise-panel orchestration-panel" v-reveal>
        <header class="enterprise-panel-head compact orchestration-head">
          <div>
            <span class="panel-kicker">智能体编排</span>
            <h2>{{ t('dashboard.agentCollaboration') }}</h2>
          </div>
          <p>{{ t('dashboard.agentCollaborationDesc') }}</p>
          <span class="source-chip">{{ t('dashboard.agentsOnline') }}</span>
        </header>

        <div class="agent-orchestration-grid">
          <RouterLink v-for="(agent, index) in sixAgents" :key="agent.no" :to="agent.to" class="agent-orchestration-card" v-reveal="index * 45">
            <span class="agent-node-no">{{ agent.no }}</span>
            <span class="agent-node-icon"><AppIcon :name="agent.icon" :size="18" /></span>
            <div>
              <b>{{ agent.name }}</b>
              <small>{{ agent.role }}</small>
            </div>
          </RouterLink>
        </div>
      </section>

      <section class="enterprise-panel workflow-roadmap execution-board" v-reveal>
        <header class="enterprise-panel-head compact">
          <div>
            <span class="panel-kicker">业务执行看板</span>
            <h2>从智能体协同到企业交付</h2>
          </div>
          <span class="source-chip light">4 类交付状态</span>
        </header>

        <div class="execution-board-body">
          <article v-for="(stage, index) in executionStages" :key="stage.no" class="execution-stage-card" v-reveal="index * 55">
            <span class="execution-stage-no">{{ stage.no }}</span>
            <div>
              <b>{{ stage.title }}</b>
              <strong>{{ stage.metric }}</strong>
              <p>{{ stage.desc }}</p>
            </div>
            <small>{{ stage.deliverable }}</small>
          </article>
        </div>
      </section>

      <section class="enterprise-insight-board" v-reveal>
        <article class="enterprise-panel stack-market-panel">
          <header class="enterprise-panel-head">
            <div>
              <span class="panel-kicker">岗位市场结构</span>
              <h2>技术栈需求热度</h2>
              <p>按治理后的岗位样本聚合，辅助判断当前市场对不同技术方向的招聘强度。</p>
            </div>
            <div class="panel-head-metric">
              <b>{{ Number(stackTotal || 0).toLocaleString() }}</b>
              <span>分析样本</span>
            </div>
          </header>

          <div class="stack-market-list">
            <div v-for="(stack, index) in topStacks" :key="stack.name" class="stack-market-row" v-reveal="index * 35">
              <span class="stack-rank">{{ String(index + 1).padStart(2, '0') }}</span>
              <div class="stack-row-main">
                <div class="stack-row-head">
                  <b>{{ stack.name || '未分类' }}</b>
                  <span>{{ Number(stack.value || 0).toLocaleString() }} 条岗位</span>
                </div>
                <ProgressBar :value="Number(stack.value || 0)" :max="maxStack" :tone="index === 1 || index === 4 ? 'gold' : 'mint'" />
              </div>
            </div>
          </div>
        </article>

        <article class="enterprise-panel trust-command-panel" v-reveal="60">
          <header class="enterprise-panel-head">
            <div>
              <span class="panel-kicker">可信运行</span>
              <h2>数据质量与证据状态</h2>
              <p>从数据质量、技能证据和审核队列三个维度展示系统是否适合进入匹配决策。</p>
            </div>
            <span class="source-chip">{{ sourceLabel }}</span>
          </header>

          <div class="trust-command-body">
            <div class="trust-ring" :style="{ background: `conic-gradient(#0f766e ${trustScore * 3.6}deg, #e2e8f0 0deg)` }">
              <div>
                <strong>{{ trustScore }}%</strong>
                <span>{{ t('dashboard.trustIndex') }}</span>
              </div>
            </div>

            <div class="trust-command-main">
              <div class="trust-kpi-grid">
                <div><b>{{ data.pendingAudits }}</b><span>{{ t('dashboard.pendingAudits') }}</span></div>
                <div><b>{{ Number(data.metrics?.relations || 0).toLocaleString() }}</b><span>{{ t('dashboard.skillCount') }}</span></div>
                <div><b>{{ Number(data.metrics?.roles || 0).toLocaleString() }}</b><span>{{ t('dashboard.roleCount') }}</span></div>
              </div>
              <div class="trust-checks">
                <span v-for="item in trustChecks" :key="item"><AppIcon name="check" :size="13" />{{ item }}</span>
              </div>
            </div>
          </div>
        </article>

        <article class="enterprise-panel insight-chart-panel trend-insight-panel">
          <header class="enterprise-panel-head compact">
            <div>
              <span class="panel-kicker">趋势洞察</span>
              <h2>岗位年度数据量</h2>
            </div>
            <span class="source-chip light">2019-2026</span>
          </header>
          <EChart :option="trendOption" height="330px" />
        </article>

        <article class="enterprise-panel insight-chart-panel skill-rank-panel">
          <header class="enterprise-panel-head compact">
            <div>
              <span class="panel-kicker">能力热词</span>
              <h2>高频技能点排行</h2>
            </div>
            <span class="source-chip light">Top 8</span>
          </header>
          <EChart :option="skillOption" height="330px" />
        </article>
      </section>
    </template>
  </div>
</template>
