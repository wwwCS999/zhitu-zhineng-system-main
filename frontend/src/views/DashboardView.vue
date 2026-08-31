<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api'
import EChart from '@/components/EChart.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import AppIcon from '@/components/AppIcon.vue'
import { useEnglishThemeText } from '@/composables/useEnglishThemeText'

const data = ref<any>(null)
const loading = ref(true)
const running = ref(false)
const error = ref('')
const pipelineNotice = ref('')
const { t } = useI18n()
const { term } = useEnglishThemeText()

const sixAgents = computed(() => [
  { no: '01', name: t('agent.dataGovernance'), icon: 'database', role: t('dashboard.agentRoleGovernance'), to: '/parsing' },
  { no: '02', name: t('agent.jobInsight'), icon: 'spark', role: t('dashboard.agentRoleInsight'), to: '/emerging' },
  { no: '03', name: t('agent.capabilityGraph'), icon: 'network', role: t('dashboard.agentRoleGraph'), to: '/graph' },
  { no: '04', name: t('agent.matching'), icon: 'match', role: t('dashboard.agentRoleMatching'), to: '/matching' },
  { no: '05', name: t('agent.learning'), icon: 'route', role: t('dashboard.agentRoleLearning'), to: '/learning' },
  { no: '06', name: t('agent.trustAudit'), icon: 'audit', role: t('dashboard.agentRoleAudit'), to: '/audit' }
])

const executionStages = computed(() => [
  { no: 'A1', title: t('dashboard.stageDataTitle'), metric: t('dashboard.stageDataMetric'), desc: t('dashboard.stageDataDesc'), deliverable: t('dashboard.stageDataDeliverable') },
  { no: 'A2', title: t('dashboard.stageGraphTitle'), metric: t('dashboard.stageGraphMetric'), desc: t('dashboard.stageGraphDesc'), deliverable: t('dashboard.stageGraphDeliverable') },
  { no: 'A3', title: t('dashboard.stageDecisionTitle'), metric: t('dashboard.stageDecisionMetric'), desc: t('dashboard.stageDecisionDesc'), deliverable: t('dashboard.stageDecisionDeliverable') },
  { no: 'A4', title: t('dashboard.stageAuditTitle'), metric: t('dashboard.stageAuditMetric'), desc: t('dashboard.stageAuditDesc'), deliverable: t('dashboard.stageAuditDeliverable') }
])

const heroSignals = computed(() => [
  { label: t('dashboard.heroSignalDataLabel'), value: t('dashboard.heroSignalDataValue'), icon: 'database' },
  { label: t('dashboard.heroSignalCapabilityLabel'), value: t('dashboard.heroSignalCapabilityValue'), icon: 'network' },
  { label: t('dashboard.heroSignalDecisionLabel'), value: t('dashboard.heroSignalDecisionValue'), icon: 'match' }
])

const capabilityPillars = computed(() => [
  { title: t('dashboard.pillarGovernanceTitle'), outcome: t('dashboard.pillarGovernanceOutcome'), desc: t('dashboard.pillarGovernanceDesc'), icon: 'database', tone: 'blue' },
  { title: t('dashboard.pillarInsightTitle'), outcome: t('dashboard.pillarInsightOutcome'), desc: t('dashboard.pillarInsightDesc'), icon: 'spark', tone: 'amber' },
  { title: t('dashboard.pillarGraphTitle'), outcome: t('dashboard.pillarGraphOutcome'), desc: t('dashboard.pillarGraphDesc'), icon: 'network', tone: 'green' },
  { title: t('dashboard.pillarMatchingTitle'), outcome: t('dashboard.pillarMatchingOutcome'), desc: t('dashboard.pillarMatchingDesc'), icon: 'match', tone: 'blue' },
  { title: t('dashboard.pillarLearningTitle'), outcome: t('dashboard.pillarLearningOutcome'), desc: t('dashboard.pillarLearningDesc'), icon: 'route', tone: 'green' },
  { title: t('dashboard.pillarAuditTitle'), outcome: t('dashboard.pillarAuditOutcome'), desc: t('dashboard.pillarAuditDesc'), icon: 'audit', tone: 'rose' }
])

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
      ? (result?.message || t('dashboard.pipelineDegraded'))
      : t('dashboard.pipelineDone')
    await load(false)
  } catch (err: any) {
    try {
      data.value = await api.dashboard(false)
      pipelineNotice.value = `${t('dashboard.pipelineFallbackPrefix')}${err.message}`
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
const sourceLabel = computed(() => data.value?.dataSource === 'MYSQL_GOVERNED_MILLION_JD' ? t('dashboard.dataSourceGoverned') : t('dashboard.dataSourceDemo'))
const trustChecks = computed(() => [
  t('dashboard.trustCheckTraceable'),
  t('dashboard.trustCheckEvidence'),
  t('dashboard.trustCheckAuditable')
])

const executiveKpis = computed(() => [
  { label: t('dashboard.governedJobs'), value: data.value?.metrics?.jobs ?? 0, desc: t('dashboard.kpiGovernedDesc'), icon: 'database', tone: 'blue' },
  { label: t('dashboard.skillRelations'), value: data.value?.metrics?.relations ?? 0, desc: t('dashboard.kpiRelationsDesc'), icon: 'network', tone: 'green' },
  { label: t('dashboard.rolesCandidates'), value: `${data.value?.metrics?.roles ?? 0} / ${data.value?.metrics?.emerging ?? 0}`, desc: t('dashboard.kpiRolesDesc'), icon: 'spark', tone: 'amber' },
  { label: t('dashboard.matchReports'), value: data.value?.metrics?.matches ?? 0, desc: t('dashboard.kpiReportsDesc'), icon: 'match', tone: 'rose' }
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
    data: (data.value?.topSkills || []).slice(0, 8).map((item: any) => term(item.name)),
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

      <aside class="enterprise-hero-aside" :aria-label="t('dashboard.heroAsideTitle')">
        <div class="hero-aside-head">
          <span>{{ t('dashboard.heroAsideTitle') }}</span>
          <b>{{ t('dashboard.heroAsideConsole') }}</b>
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
          <span>{{ t('dashboard.heroSummaryLabel') }}</span>
          <b>{{ t('dashboard.heroSummary') }}</b>
        </div>
      </aside>
    </section>

    <div v-if="error" class="inline-alert error"><b>{{ t('dashboard.processingFailed') }}</b><span>{{ error }}</span></div>
    <div v-else-if="pipelineNotice" class="inline-alert success"><b>{{ t('dashboard.pipelineStatus') }}</b><span>{{ pipelineNotice }}</span></div>

    <div v-if="loading" class="loading-layout" :aria-label="t('dashboard.loadingAria')">
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
              <span class="panel-kicker">{{ t('dashboard.solutionKicker') }}</span>
              <h2>{{ t('dashboard.solutionTitle') }}</h2>
              <p>{{ t('dashboard.solutionDesc') }}</p>
            </div>
            <span class="source-chip light">{{ t('dashboard.recruitingOs') }}</span>
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
              <span>{{ t('dashboard.deliveryInputLabel') }}</span>
              <b>{{ t('dashboard.deliveryInputValue') }}</b>
            </div>
            <div>
              <span>{{ t('dashboard.deliveryModelLabel') }}</span>
              <b>{{ t('dashboard.deliveryModelValue') }}</b>
            </div>
            <div>
              <span>{{ t('dashboard.deliveryDecisionLabel') }}</span>
              <b>{{ t('dashboard.deliveryDecisionValue') }}</b>
            </div>
            <div>
              <span>{{ t('dashboard.deliveryGovernLabel') }}</span>
              <b>{{ t('dashboard.deliveryGovernValue') }}</b>
            </div>
          </div>
        </article>

      </section>

      <section class="enterprise-panel orchestration-panel" v-reveal>
        <header class="enterprise-panel-head compact orchestration-head">
          <div>
            <span class="panel-kicker">{{ t('dashboard.orchestrationKicker') }}</span>
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
            <span class="panel-kicker">{{ t('dashboard.executionKicker') }}</span>
            <h2>{{ t('dashboard.executionTitle') }}</h2>
          </div>
          <span class="source-chip light">{{ t('dashboard.executionStatus') }}</span>
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
              <span class="panel-kicker">{{ t('dashboard.marketKicker') }}</span>
              <h2>{{ t('dashboard.marketTitle') }}</h2>
              <p>{{ t('dashboard.marketDesc') }}</p>
            </div>
            <div class="panel-head-metric">
              <b>{{ Number(stackTotal || 0).toLocaleString() }}</b>
              <span>{{ t('dashboard.sampleCount') }}</span>
            </div>
          </header>

          <div class="stack-market-list">
            <div v-for="(stack, index) in topStacks" :key="stack.name" class="stack-market-row" v-reveal="index * 35">
              <span class="stack-rank">{{ String(index + 1).padStart(2, '0') }}</span>
              <div class="stack-row-main">
                <div class="stack-row-head">
                  <b>{{ term(stack.name) || t('dashboard.uncategorized') }}</b>
                  <span>{{ Number(stack.value || 0).toLocaleString() }} {{ t('dashboard.jobUnit') }}</span>
                </div>
                <ProgressBar :value="Number(stack.value || 0)" :max="maxStack" :tone="index === 1 || index === 4 ? 'gold' : 'mint'" />
              </div>
            </div>
          </div>
        </article>

        <article class="enterprise-panel trust-command-panel" v-reveal="60">
          <header class="enterprise-panel-head">
            <div>
              <span class="panel-kicker">{{ t('dashboard.trustKicker') }}</span>
              <h2>{{ t('dashboard.trustTitle') }}</h2>
              <p>{{ t('dashboard.trustDesc') }}</p>
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
              <span class="panel-kicker">{{ t('dashboard.trendKicker') }}</span>
              <h2>{{ t('dashboard.yearTrend') }}</h2>
            </div>
            <span class="source-chip light">2019-2026</span>
          </header>
          <EChart :option="trendOption" height="330px" />
        </article>

        <article class="enterprise-panel insight-chart-panel skill-rank-panel">
          <header class="enterprise-panel-head compact">
            <div>
              <span class="panel-kicker">{{ t('dashboard.skillKicker') }}</span>
              <h2>{{ t('dashboard.topSkillsRank') }}</h2>
            </div>
            <span class="source-chip light">Top 8</span>
          </header>
          <EChart :option="skillOption" height="330px" />
        </article>
      </section>
    </template>
  </div>
</template>
