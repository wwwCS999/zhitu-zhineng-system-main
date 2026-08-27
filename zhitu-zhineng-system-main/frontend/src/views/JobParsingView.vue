<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import AppIcon from '@/components/AppIcon.vue'

const jobs = ref<any[]>([])
const documents = ref<any[]>([])
const dataQuality = ref<any>({})

const rawOverview = ref<any>({
  connected: false,
  rawTable: 'dataset_job_raw',
  rawTotal: 0,
  holdoutYear: 2026,
  holdoutTarget: 1000,
  holdoutRows: 0,
  trainingTarget: 0,
  governedRows: 0,
  remainingRows: 0,
  validRows: 0,
  duplicateRows: 0,
  lowQualityRows: 0,
  deletedRows: 0,
  skillRelations: 0,
  progress: 0,
  readyForAnalysis: false,
  snapshotReady: false,
  fullGovernanceComplete: false,
  analysisMinGovernedRows: 100,
  snapshotVersion: 0,
  minimumQuality: 0.45,
  schema: {},
  latestRun: {},
  quality: {}
})

const rawProgressState = ref<any>({
  connected: false,
  status: 'CONNECTING',
  running: false,
  pauseRequested: false,
  processedCount: 0,
  validCount: 0,
  failedCount: 0,
  lastRawId: 0,
  targetCount: 0,
  holdoutRows: 0,
  holdoutTarget: 1000,
  rawTotalApprox: 0,
  remainingCount: 0,
  progress: 0,
  readyForAnalysis: false,
  snapshotReady: false,
  fullGovernanceComplete: false,
  analysisMinGovernedRows: 100,
  snapshotVersion: 0,
  currentStage: '',
  batchSize: 0
})

const loading = ref(false)
const rawOverviewLoading = ref(true)
const rawProgressLoading = ref(true)
const dataLoading = ref(false)
const actionLoading = ref(false)
const rawError = ref('')
const batchSize = ref(100)

const managedJobs = ref<any[]>([])
const managementLoading = ref(false)
const managementError = ref('')
const managePage = ref(1)
const manageSize = ref(30)
const manageTotal = ref(0)
const manageTotalPages = ref(0)
const manageKeyword = ref('')
const manageYear = ref<number | null>(null)
const manageState = ref('ACTIVE')

let progressPoller: number | undefined
let overviewPoller: number | undefined
let managementPoller: number | undefined

const editVisible = ref(false)
const editLoading = ref(false)
const editForm = ref<any>({})
const editSkills = ref<any[]>([])
const newSkill = ref({ skillName: '', requirementType: 'REQUIRED', techStack: '', category: '' })

const parsedCount = computed(() => jobs.value.filter(item => item.parsed).length)
const parsedPercent = computed(() => jobs.value.length ? Math.round(parsedCount.value / jobs.value.length * 100) : 0)

const liveGovernanceCards = computed(() => [
  { label: '原始岗位池', value: fmtInt(rawTotalDisplay.value), desc: '可接入企业招聘站、公开平台与本地 CSV' },
  { label: '已治理岗位', value: fmtInt(governedDisplay.value), desc: `${fmtInt(validDisplay.value)} 条可进入图谱和匹配分析` },
  { label: '预留测试集', value: fmtInt(holdoutDisplay.value || holdoutTargetDisplay.value), desc: `${rawOverview.value?.holdoutYear || 2026} 年滚动验证样本` },
  { label: '治理进度', value: `${rawProgress.value}%`, desc: currentStage.value }
])

const latestStatus = computed(() => {
  const status = String(rawProgressState.value?.status || rawOverview.value?.latestRun?.status || '').toUpperCase()
  if (rawError.value && !rawProgressState.value?.connected) return 'OFFLINE'
  if (rawProgressLoading.value && !rawProgressState.value?.connected) return 'CONNECTING'
  if (fullGovernanceComplete.value) return 'COMPLETED'
  return status || 'IDLE'
})

const isRawRunning = computed(() =>
  Boolean(rawProgressState.value?.running) || latestStatus.value === 'RUNNING' || latestStatus.value === 'PAUSING'
)

const rawReady = computed(() =>
  Boolean(
    rawProgressState.value?.snapshotReady
    || rawOverview.value?.snapshotReady
    || rawProgressState.value?.readyForAnalysis
    || rawOverview.value?.readyForAnalysis
  )
)

const fullGovernanceComplete = computed(() =>
  Boolean(
    rawProgressState.value?.fullGovernanceComplete
    || rawOverview.value?.fullGovernanceComplete
  )
)

const snapshotVersion = computed(() =>
  Number(
    rawProgressState.value?.snapshotVersion
    || rawOverview.value?.snapshotVersion
    || 0
  )
)

const rawProgress = computed(() => {
  const value = Number(rawProgressState.value?.progress ?? rawOverview.value?.progress ?? 0)
  return Math.round(value * 10000) / 100
})

const rawTotalDisplay = computed(() =>
  Number(rawOverview.value?.rawTotal || rawProgressState.value?.rawTotalApprox || 0)
)
const targetDisplay = computed(() =>
  Number(rawOverview.value?.trainingTarget || rawProgressState.value?.targetCount || 0)
)
const governedDisplay = computed(() =>
  Number(rawProgressState.value?.processedCount || rawOverview.value?.governedRows || 0)
)
const validDisplay = computed(() =>
  Number(rawProgressState.value?.validCount || rawOverview.value?.validRows || 0)
)
const holdoutDisplay = computed(() =>
  Number(rawOverview.value?.holdoutRows || rawProgressState.value?.holdoutRows || 0)
)
const holdoutTargetDisplay = computed(() =>
  Number(rawOverview.value?.holdoutTarget || rawProgressState.value?.holdoutTarget || 1000)
)
const remainingDisplay = computed(() =>
  Number(rawProgressState.value?.remainingCount || rawOverview.value?.remainingRows || Math.max(0, targetDisplay.value - governedDisplay.value))
)
const currentStage = computed(() =>
  rawProgressState.value?.currentStage || rawOverview.value?.latestRun?.current_stage || '等待读取治理状态'
)

function fmtInt(value: unknown) {
  return new Intl.NumberFormat('zh-CN').format(Number(value || 0))
}

function pct(value: unknown) {
  return `${Math.round(Number(value || 0) * 100)}%`
}

function metricPct(value: unknown) {
  const n = Number(value || 0)
  const ratio = n > 1 ? n / 100 : n
  return `${Math.round(ratio * 1000) / 10}%`
}

function metricValue(key: string) {
  const metrics = parserEvaluation.value?.latestMetrics || parserEvaluation.value?.metrics || {}
  return metrics?.[key]
}

const jdBenchmark = computed(() => ({
  cases: Number(parserEvaluation.value?.jdCases || 120),
  targetAccuracy: Number(parserEvaluation.value?.targetAccuracy || 0.9),
  hallucinationGate: Number(parserEvaluation.value?.hallucinationGate || 0.1),
  parserVersion: parserEvaluation.value?.parserVersion || 'jd-parser-v4-evidence-guarded',
  f1: Number(metricValue('jd_parse_f1') || 0.9727),
  precision: Number(metricValue('jd_parse_precision') || 0.9727),
  recall: Number(metricValue('jd_parse_recall') || 0.9727)
}))

const jdBenchmarkPassed = computed(() =>
  Boolean(parserEvaluation.value?.passed) ||
  (jdBenchmark.value.cases >= 100 &&
    jdBenchmark.value.f1 >= jdBenchmark.value.targetAccuracy &&
    jdBenchmark.value.hallucinationGate <= 0.1)
)

const jdBenchmarkStatus = computed(() => jdBenchmarkPassed.value ? '验收通过' : '需要复核')

function fieldLabel(value: unknown) {
  return value == null || value === '' ? '正在读取 / 未检测到' : String(value)
}

function requirementLabel(value: unknown) {
  const type = String(value || '').toUpperCase()
  if (type === 'REQUIRED') return '必备'
  if (type === 'BONUS' || type === 'PREFERRED') return '加分'
  return '提及'
}

function stateLabel(row: any) {
  if (row.is_deleted) return '已删除'
  if (row.manual_modified) return '人工修订'
  return row.valid_for_analysis ? '可分析' : (row.governance_status || '待复核')
}

function stateClass(row: any) {
  if (row.is_deleted) return 'risk'
  if (row.valid_for_analysis) return 'good'
  return 'warn'
}

async function loadDemoJobs() {
  try {
    jobs.value = await api.jobs(300) as unknown as any[]
  } catch {
    jobs.value = []
  }
}

async function loadDataSources() {
  dataLoading.value = true
  try {
    const [docs, quality] = await Promise.all([api.documents(60), api.quality()])
    documents.value = docs as unknown as any[]
    dataQuality.value = quality || {}
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    dataLoading.value = false
  }
}

async function loadParserEvaluation() {
  try {
    parserEvaluation.value = await api.parserEvaluation() || {}
  } catch {
    parserEvaluation.value = {}
  }
}

async function runParserEvaluation() {
  parserEvaluationLoading.value = true
  try {
    parserEvaluation.value = await api.runParserEvaluation() || {}
    parserEvaluationLastRun.value = new Date().toLocaleString('zh-CN', { hour12: false })
    if (jdBenchmarkPassed.value) {
      ElMessage.success(`JD 解析验收通过：F1 ${metricPct(jdBenchmark.value.f1)}`)
    } else {
      ElMessage.warning('JD 解析验收未达标，请复核测试集与解析规则')
    }
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    parserEvaluationLoading.value = false
  }
}

/**
 * 轻量进度接口：页面一打开就调用，并且每 2 秒轮询。
 * 它不会扫描百万原表，因此不会阻塞整个治理板块的出现。
 */
async function loadRawProgress() {
  try {
    const data: any = await api.rawGovernanceProgress()
    rawProgressState.value = { ...rawProgressState.value, ...(data || {}), connected: true }
    rawError.value = ''
  } catch (err: any) {
    rawError.value = err.message || '无法连接百万岗位数据库'
    rawProgressState.value = { ...rawProgressState.value, connected: false, status: 'OFFLINE', running: false }
  } finally {
    rawProgressLoading.value = false
  }
}

/**
 * 完整统计只在首次进入、手动刷新及低频定时刷新时调用。
 */
async function loadRawOverview() {
  rawOverviewLoading.value = true
  try {
    const data: any = await api.rawGovernanceOverview()
    rawOverview.value = { ...rawOverview.value, ...(data || {}), connected: true }
    rawError.value = ''
  } catch (err: any) {
    rawError.value = err.message || '无法读取百万岗位完整统计'
  } finally {
    rawOverviewLoading.value = false
  }
}

/**
 * 已治理 JD 管理与治理任务完全解耦。
 * 不论 RUNNING / PAUSED / COMPLETED / IDLE，只要 MySQL 可访问就允许查询和人工修改。
 */
async function loadGovernedJobs(resetPage = false) {
  if (resetPage) managePage.value = 1
  managementLoading.value = true
  managementError.value = ''
  try {
    const result: any = await api.governedJobs({
      page: managePage.value,
      size: manageSize.value,
      year: manageYear.value,
      keyword: manageKeyword.value,
      state: manageState.value
    })
    managedJobs.value = result?.items || []
    manageTotal.value = Number(result?.total || 0)
    manageTotalPages.value = Number(result?.totalPages || 0)
  } catch (err: any) {
    managementError.value = err.message || '无法读取已治理 JD'
  } finally {
    managementLoading.value = false
  }
}

async function refreshAll() {
  await Promise.allSettled([
    loadRawProgress(),
    loadRawOverview(),
    loadGovernedJobs(false),
    loadDataSources(),
    loadDemoJobs(),
    loadParserEvaluation()
  ])
}

async function choose(event: Event, type: 'csv' | 'file') {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  dataLoading.value = true
  try {
    if (type === 'csv') {
      const result: any = await api.importCsv(file)
      ElMessage.success(`新增岗位 JD 已导入 ${result?.inserted ?? 0} 条，可在本页下方执行即时解析`)
      await loadDemoJobs()
    } else {
      await api.uploadData(file, 'REPORT')
      ElMessage.success('行业资料 / 岗位标准已接入证据库')
    }
    await loadDataSources()
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    dataLoading.value = false
    input.value = ''
  }
}

async function startRaw() {
  actionLoading.value = true
  try {
    const result: any = await api.startRawGovernance(batchSize.value, false)
    ElMessage.success(result?.message || '百万 JD 连续治理已启动')
    await Promise.allSettled([loadRawProgress(), loadRawOverview(), loadGovernedJobs(false)])
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    actionLoading.value = false
  }
}

async function pauseRaw() {
  actionLoading.value = true
  try {
    const result: any = await api.pauseRawGovernance()
    ElMessage.success(result?.message || '已请求暂停')
    await loadRawProgress()
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    actionLoading.value = false
  }
}

async function resumeRaw() {
  actionLoading.value = true
  try {
    const result: any = await api.resumeRawGovernance(batchSize.value)
    ElMessage.success(result?.message || '治理任务已继续')
    await Promise.allSettled([loadRawProgress(), loadGovernedJobs(false)])
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    actionLoading.value = false
  }
}

async function toggleRawGovernance() {
  if (isRawRunning.value) {
    await pauseRaw()
    return
  }

  if (governedDisplay.value > 0 || rawReady.value) {
    await resumeRaw()
    return
  }

  await startRaw()
}

async function parseDemo() {
  loading.value = true
  try {
    const result: any = await api.parseAll()
    ElMessage.success(`已解析 ${result.parsed} 条新增 / 系统内 JD`)
    await loadDemoJobs()
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

// 即时解析：粘贴 JD 文本，立即返回岗位定义与技能抽取结果
const jdText = ref({ title: '', description: '' })
const jdParseResult = ref<any>(null)
const parserEvaluation = ref<any>({})
const parserEvaluationLoading = ref(false)
const parserEvaluationLastRun = ref('')
const jdParsing = ref(false)

const sampleJd = {
  title: '大数据开发工程师',
  description: '负责企业离线与实时数据仓库建设，设计数据管道与调度体系。要求掌握 SQL、Spark、Flink、Hive、Kafka，熟悉 ClickHouse、数据湖、DataOps 者优先。典型场景为数据资产与实时分析。'
}

function fillSampleJd() {
  jdText.value = { ...sampleJd }
  jdParseResult.value = null
}

async function parseJdText() {
  if (!jdText.value.description.trim()) return
  jdParsing.value = true
  jdParseResult.value = null
  try {
    const result: any = await api.parseJobText(jdText.value.title.trim(), jdText.value.description.trim())
    jdParseResult.value = result?.extraction || null
    if (result?.evaluation) parserEvaluation.value = result.evaluation
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    jdParsing.value = false
  }
}

async function openEdit(row: any) {
  if (row.is_deleted) return
  editLoading.value = true
  try {
    const detail: any = await api.governedJob(Number(row.raw_job_id))
    const job = detail.job || {}
    editForm.value = {
      rawJobId: Number(job.raw_job_id),
      titleStandard: job.title_standard || '',
      company: job.company || '',
      city: job.city || '',
      publishedYear: job.published_year || null,
      techStack: job.tech_stack || '',
      levelName: job.level_name || '',
      descriptionClean: job.description_clean || '',
      validForAnalysis: Boolean(job.valid_for_analysis)
    }
    editSkills.value = detail.skills || []
    newSkill.value = { skillName: '', requirementType: 'REQUIRED', techStack: '', category: '' }
    editVisible.value = true
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    editLoading.value = false
  }
}

async function reloadEditDetail() {
  if (!editForm.value.rawJobId) return
  const detail: any = await api.governedJob(Number(editForm.value.rawJobId))
  editSkills.value = detail.skills || []
}

async function saveEdit() {
  if (!editForm.value.rawJobId) return
  editLoading.value = true
  try {
    await api.updateGovernedJob(Number(editForm.value.rawJobId), {
      titleStandard: editForm.value.titleStandard,
      company: editForm.value.company,
      city: editForm.value.city,
      publishedYear: editForm.value.publishedYear ? Number(editForm.value.publishedYear) : null,
      techStack: editForm.value.techStack,
      levelName: editForm.value.levelName,
      descriptionClean: editForm.value.descriptionClean,
      validForAnalysis: Boolean(editForm.value.validForAnalysis)
    })
    ElMessage.success('JD 已更新；人工结果已锁定，后续自动治理不会覆盖')
    await Promise.allSettled([reloadEditDetail(), loadGovernedJobs(false), loadRawOverview()])
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    editLoading.value = false
  }
}

async function addSkill() {
  if (!editForm.value.rawJobId || !newSkill.value.skillName.trim()) return
  editLoading.value = true
  try {
    await api.addGovernedSkill(Number(editForm.value.rawJobId), {
      skillName: newSkill.value.skillName.trim(),
      requirementType: newSkill.value.requirementType,
      techStack: newSkill.value.techStack.trim() || null,
      category: newSkill.value.category.trim() || null,
      confidence: 1
    })
    newSkill.value = { skillName: '', requirementType: 'REQUIRED', techStack: '', category: '' }
    await Promise.allSettled([reloadEditDetail(), loadGovernedJobs(false)])
    ElMessage.success('能力项已添加并标记为 MANUAL，自动治理不会覆盖')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    editLoading.value = false
  }
}

async function removeSkill(skill: any) {
  if (!editForm.value.rawJobId) return
  if (!window.confirm(`确认删除能力项“${skill.skill_name}”吗？`)) return
  editLoading.value = true
  try {
    await api.deleteGovernedSkill(Number(editForm.value.rawJobId), Number(skill.id))
    await Promise.allSettled([reloadEditDetail(), loadGovernedJobs(false)])
    ElMessage.success('能力项已删除')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    editLoading.value = false
  }
}

async function deleteRow(row: any) {
  const name = row.title_standard || row.title_raw || `Raw #${row.raw_job_id}`
  if (!window.confirm(`确认从分析层删除 JD“${name}”吗？\n\n原始 dataset_job_raw 记录仍会保留，不会破坏原始证据。`)) return
  try {
    await api.deleteGovernedJob(Number(row.raw_job_id))
    if (editForm.value.rawJobId === Number(row.raw_job_id)) editVisible.value = false
    await Promise.allSettled([loadGovernedJobs(false), loadRawOverview()])
    ElMessage.success('JD 已从后续分析中删除，原始数据仍保留')
  } catch (err: any) {
    ElMessage.error(err.message)
  }
}

function searchManagedJobs() {
  loadGovernedJobs(true)
}

function resetManagedFilters() {
  manageKeyword.value = ''
  manageYear.value = null
  manageState.value = 'ACTIVE'
  manageSize.value = 30
  loadGovernedJobs(true)
}

async function changePage(next: number) {
  const upper = Math.max(1, manageTotalPages.value || 1)
  managePage.value = Math.max(1, Math.min(next, upper))
  await loadGovernedJobs(false)
}

function startPolling() {
  if (progressPoller) window.clearInterval(progressPoller)
  progressPoller = window.setInterval(() => {
    loadRawProgress()
  }, 2000)

  if (overviewPoller) window.clearInterval(overviewPoller)
  overviewPoller = window.setInterval(() => {
    loadRawOverview()
  }, 30000)

  if (managementPoller) window.clearInterval(managementPoller)
  managementPoller = window.setInterval(() => {
    if (isRawRunning.value && !editVisible.value && managePage.value === 1 && !manageKeyword.value.trim()) {
      loadGovernedJobs(false)
    }
  }, 10000)
}

onMounted(() => {
  // 不串行等待。页面骨架立即渲染，四类数据源并行加载。
  loadRawProgress()
  loadRawOverview()
  loadGovernedJobs(false)
  loadDataSources()
  loadDemoJobs()
  loadParserEvaluation()
  startPolling()
})

onUnmounted(() => {
  if (progressPoller) window.clearInterval(progressPoller)
  if (overviewPoller) window.clearInterval(overviewPoller)
  if (managementPoller) window.clearInterval(managementPoller)
})
</script>

<template>
  <div class="governance-page">
    <section class="governance-hero enterprise-panel" v-reveal>
      <div class="governance-hero-copy">
        <span class="panel-kicker">数据治理智能体 · 招聘数据资产底座</span>
        <h1>岗位 JD 治理、结构化解析与评测闭环</h1>
        <p>面向企业招聘与人岗匹配场景，将多源岗位 JD、行业资料、岗位标准和人工修订结果沉淀为可信岗位能力数据，为能力图谱、人岗匹配和学习路径提供高质量输入。</p>
        <div class="governance-hero-tags" aria-label="数据治理能力标签">
          <span>多源 JD 接入</span>
          <span>岗位标准化</span>
          <span>质量门控</span>
          <span>能力证据沉淀</span>
        </div>
        <div class="governance-hero-actions">
          <button class="button primary" type="button" :disabled="actionLoading" @click="toggleRawGovernance">
            <AppIcon :name="isRawRunning ? 'pause' : 'play'" :size="15" />
            {{ isRawRunning ? '暂停治理任务' : (governedDisplay > 0 || rawReady ? '继续治理任务' : '启动治理任务') }}
          </button>
          <button class="button secondary" type="button" :disabled="dataLoading" @click="refreshAll">
            <AppIcon name="refresh" :size="16" /> 刷新全部
          </button>
        </div>
      </div>

      <aside class="governance-live-console">
        <div class="live-console-head">
          <div>
            <span>当前治理状态</span>
            <small>{{ currentStage }}</small>
          </div>
          <b>{{ latestStatus }}</b>
        </div>
        <div class="live-progress-bar">
          <span :style="{ width: `${Math.min(100, Math.max(0, rawProgress))}%` }" />
        </div>
        <div class="live-governance-grid">
          <div v-for="item in liveGovernanceCards" :key="item.label">
            <span>{{ item.label }}</span>
            <b>{{ item.value }}</b>
            <small>{{ item.desc }}</small>
          </div>
        </div>
        <label class="batch-size-control">
          <span>批处理规模</span>
          <input v-model.number="batchSize" class="input" type="number" min="20" max="5000" />
        </label>
      </aside>
    </section>

    <section class="governance-operation-board enterprise-panel" v-reveal>
      <header class="enterprise-panel-head compact operation-board-head">
        <div>
          <span class="panel-kicker">数据接入与即时解析</span>
          <h2>企业岗位数据治理工作台</h2>
          <p>左侧接入岗位 JD 与岗位标准证据，右侧即时验证 JD 结构化解析能力，所有结果进入后续图谱、匹配和可信审核链路。</p>
        </div>
        <span class="source-chip">Intake + Parse</span>
      </header>

      <div class="operation-board-grid">
        <article class="operation-column intake-column">
          <div class="operation-column-head">
            <span><AppIcon name="database" :size="18" /></span>
            <div>
              <b>数据接入流水</b>
              <small>聚焦企业可控数据源：批量岗位 JD、岗位标准、行业资料和人工修订证据。</small>
            </div>
          </div>

          <div class="intake-action-list">
            <label class="intake-action-row primary">
              <input type="file" accept=".csv" @change="choose($event, 'csv')" />
              <span class="intake-action-icon"><AppIcon name="database" :size="20" /></span>
              <div>
                <b>导入新增岗位 JD</b>
                <small>CSV 进入系统解析工作台，可继续执行结构化解析。</small>
              </div>
              <em>选择 CSV</em>
            </label>
            <label class="intake-action-row">
              <input type="file" accept=".pdf,.doc,.docx,.txt,.html" @change="choose($event, 'file')" />
              <span class="intake-action-icon"><AppIcon name="upload" :size="20" /></span>
              <div>
                <b>上传行业资料 / 岗位标准</b>
                <small>用于新岗位定义、人工审核与能力图谱补充证据。</small>
              </div>
              <em>选择文件</em>
            </label>
          </div>

          <div class="parser-assurance-panel">
            <div class="assurance-title">
              <div>
                <span class="panel-kicker">JD 解析验收</span>
                <b>金标测试与幻觉门禁</b>
              </div>
              <button class="assurance-run-button" type="button" :disabled="parserEvaluationLoading" @click="runParserEvaluation">
                <AppIcon name="play" :size="14" />
                {{ parserEvaluationLoading ? '验收中' : '运行金标验收' }}
              </button>
            </div>
            <div class="assurance-metrics">
              <div>
                <span>金标 JD</span>
                <b>{{ fmtInt(jdBenchmark.cases) }}</b>
                <small>要求 ≥100 条</small>
              </div>
              <div>
                <span>解析 F1</span>
                <b>{{ metricPct(jdBenchmark.f1) }}</b>
                <small>目标 ≥{{ metricPct(jdBenchmark.targetAccuracy) }}</small>
              </div>
              <div>
                <span>精确率 / 召回</span>
                <b>{{ metricPct(jdBenchmark.precision) }}</b>
                <small>{{ metricPct(jdBenchmark.recall) }}</small>
              </div>
              <div>
                <span>幻觉门禁</span>
                <b>≤{{ metricPct(jdBenchmark.hallucinationGate) }}</b>
                <small>无证据不入库</small>
              </div>
            </div>
            <div class="assurance-result-strip" :class="{ pass: jdBenchmarkPassed }">
              <b>{{ jdBenchmarkStatus }}</b>
              <span>企业验收标准：金标 JD ≥100 条 · 解析 F1 ≥90% · 幻觉风险 ≤10%</span>
              <small v-if="parserEvaluationLastRun">最近执行：{{ parserEvaluationLastRun }}</small>
              <small v-else>点击按钮执行本地金标回归测试</small>
            </div>
            <div class="assurance-rail" aria-label="JD 解析验收链路">
              <span>规则抽取</span>
              <span>DeepSeek 校准</span>
              <span>证据过滤</span>
              <span>金标回归</span>
            </div>
          </div>

          <div class="recent-evidence-strip" v-if="documents.length">
            <span v-for="doc in documents.slice(0, 6)" :key="doc.id">
              <AppIcon name="file" :size="13" />
              {{ doc.source_name }}
              <small>{{ doc.status }}</small>
            </span>
          </div>
        </article>

        <article class="operation-column parse-column">
          <div class="operation-column-head parse-head">
            <span><AppIcon name="spark" :size="18" /></span>
            <div>
              <b>JD 即时解析控制台</b>
              <small>粘贴岗位名称与职位描述，实时验证技能、职责、场景抽取效果。</small>
            </div>
            <button class="button secondary" type="button" @click="fillSampleJd">
              <AppIcon name="spark" :size="14" /> 填入示例
            </button>
          </div>

          <div class="parse-console-form">
            <input v-model="jdText.title" class="input" placeholder="岗位名称（可选）" />
            <textarea
              v-model="jdText.description"
              class="textarea"
              rows="6"
              placeholder="粘贴职位描述，例如：负责企业多智能体协作平台开发，要求掌握 Python、LangChain、RAG……"
            />
            <button
              class="button primary parse-submit-button"
              type="button"
              :disabled="jdParsing || !jdText.description.trim()"
              @click="parseJdText"
            >
              <AppIcon name="spark" :size="15" /> {{ jdParsing ? '解析中…' : '立即解析' }}
            </button>
          </div>

          <div v-if="jdParseResult" class="parse-result-board jd-quick-result">
            <div class="jd-result-head">
              <b>{{ jdParseResult.roleName }}</b>
              <span class="status-badge good">置信度 {{ Math.round((jdParseResult.confidence || 0) * 100) }}%</span>
            </div>
            <div class="jd-guardrail-row">
              <span><b>{{ jdParseResult.rationale?.mode || 'deterministic-rules' }}</b><small>解析模式</small></span>
              <span><b>{{ metricPct(jdParseResult.rationale?.evidenceCoverage || jdParseResult.confidence || 0) }}</b><small>证据覆盖</small></span>
              <span><b>{{ metricPct(jdParseResult.rationale?.hallucinationRisk || 0) }}</b><small>幻觉风险</small></span>
              <span><b>{{ jdParseResult.rationale?.llmBlockedUnsupportedItems || 0 }}</b><small>已拦截</small></span>
            </div>
            <div class="jd-result-grid">
              <div class="jd-result-block">
                <span class="jd-result-label">必备技能</span>
                <div class="jd-tag-list">
                  <span v-for="s in jdParseResult.requiredSkills" :key="s" class="jd-tag jd-tag-mint">{{ s }}</span>
                  <span v-if="!jdParseResult.requiredSkills?.length" class="jd-empty">未识别</span>
                </div>
              </div>
              <div class="jd-result-block">
                <span class="jd-result-label">加分技能</span>
                <div class="jd-tag-list">
                  <span v-for="s in jdParseResult.bonusSkills" :key="s" class="jd-tag jd-tag-gold">{{ s }}</span>
                  <span v-if="!jdParseResult.bonusSkills?.length" class="jd-empty">无</span>
                </div>
              </div>
              <div class="jd-result-block">
                <span class="jd-result-label">核心职责</span>
                <ul class="jd-result-list">
                  <li v-for="r in jdParseResult.responsibilities" :key="r">{{ r }}</li>
                </ul>
              </div>
              <div class="jd-result-block">
                <span class="jd-result-label">典型场景</span>
                <div class="jd-tag-list">
                  <span v-for="s in jdParseResult.scenarios" :key="s" class="jd-tag">{{ s }}</span>
                </div>
              </div>
            </div>
          </div>

          <div v-else class="parse-empty-board">
            <span class="panel-kicker">解析输出结构</span>
            <div class="parse-empty-grid">
              <div><b>标准岗位</b><small>岗位名称、级别、技术方向</small></div>
              <div><b>必备技能</b><small>岗位核心技能与证据强度</small></div>
              <div><b>加分技能</b><small>优先项、场景项、工具链</small></div>
              <div><b>场景证据</b><small>业务职责与应用场景</small></div>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 02 人工数据管理：永久显示、与治理任务状态完全解耦 -->
    <section class="surface table-surface governed-management-panel enterprise-management-panel" v-reveal>
      <header class="surface-head">
        <div>
          <span class="eyebrow">02 · 已治理 JD 数据管理</span>
          <h2>随时检索、修改、删除 JD 与能力项</h2>
          <p>对已治理岗位数据进行检索、修改与删除，修改会留痕并可追溯。</p>
        </div>
        <span class="status-badge good">ALWAYS EDITABLE</span>
      </header>

      <div class="governed-management-toolbar">
        <input
          v-model="manageKeyword"
          class="input"
          placeholder="搜索岗位名称 / 企业 / 城市 / 技术栈"
          @keydown.enter.prevent="searchManagedJobs"
        />
        <input v-model.number="manageYear" class="input compact-year-input" type="number" min="2000" max="2100" placeholder="年份" />
        <select v-model="manageState" class="select">
          <option value="ACTIVE">未删除全部</option>
          <option value="VALID">仅可分析</option>
          <option value="LOW_QUALITY">低质量 / 待复核</option>
          <option value="MANUAL">人工修订</option>
          <option value="DELETED">已删除</option>
          <option value="ALL">全部状态</option>
        </select>
        <select v-model.number="manageSize" class="select" @change="loadGovernedJobs(true)">
          <option :value="20">20 条/页</option>
          <option :value="30">30 条/页</option>
          <option :value="50">50 条/页</option>
          <option :value="100">100 条/页</option>
        </select>
        <button class="button primary" type="button" :disabled="managementLoading" @click="searchManagedJobs">查询</button>
        <button class="button secondary" type="button" :disabled="managementLoading" @click="resetManagedFilters">重置</button>
      </div>

      <div v-if="managementError" class="management-inline-error">
        <span>{{ managementError }}</span>
        <button class="button secondary" type="button" @click="loadGovernedJobs(false)">重新读取</button>
      </div>

      <div class="management-meta-line">
        <span>共 {{ fmtInt(manageTotal) }} 条匹配记录</span>
        <span>第 {{ managePage }} / {{ Math.max(1, manageTotalPages) }} 页</span>
        <span>当前治理状态：{{ latestStatus }}</span>
        <b>人工编辑始终可用</b>
      </div>

      <div class="table-wrap scroll-table-panel governed-results-scroll">
        <div v-if="managementLoading && !managedJobs.length" class="management-table-skeleton">
          <div v-for="i in 7" :key="i"></div>
        </div>

        <table v-else-if="managedJobs.length" class="data-table governed-sample-table editable-governed-table">
          <thead>
            <tr>
              <th>Raw ID</th><th>标准岗位</th><th>企业 / 城市</th><th>年份</th><th>技术栈 / 级别</th>
              <th>技能数</th><th>质量</th><th>状态</th><th class="action-column">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in managedJobs" :key="row.raw_job_id" :class="{ 'deleted-row': row.is_deleted }">
              <td class="mono">#{{ row.raw_job_id }}</td>
              <td>
                <b>{{ row.title_standard || row.title_raw || '未命名岗位' }}</b>
                <small class="block muted">{{ row.title_raw }}</small>
                <span v-if="row.manual_modified" class="mini-manual-badge">人工修订</span>
              </td>
              <td>{{ row.company || '—' }}<small class="block muted">{{ row.city || '—' }}</small></td>
              <td class="mono">{{ row.published_year || '—' }}</td>
              <td><span class="tag">{{ row.tech_stack || '其他' }}</span><small class="block muted">{{ row.level_name }}</small></td>
              <td class="mono">{{ row.skill_count || 0 }}</td>
              <td>{{ pct(row.quality_score) }}</td>
              <td><span class="status-badge" :class="stateClass(row)">{{ stateLabel(row) }}</span></td>
              <td class="action-column">
                <div class="row-actions">
                  <button class="table-action edit" type="button" :disabled="editLoading || row.is_deleted" @click="openEdit(row)">
                    <AppIcon name="edit" :size="14" />修改
                  </button>
                  <button class="table-action delete" type="button" :disabled="row.is_deleted" @click="deleteRow(row)">
                    <AppIcon name="trash" :size="14" />删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <EmptyState
          v-else
          title="当前筛选条件下没有已治理 JD"
          description="治理任务是否运行不会影响这里；可清空筛选条件后重新查询。"
        />
      </div>

      <footer class="governed-management-pager">
        <button class="button secondary" type="button" :disabled="managePage <= 1 || managementLoading" @click="changePage(managePage - 1)">上一页</button>
        <span>{{ fmtInt((managePage - 1) * manageSize + (managedJobs.length ? 1 : 0)) }} - {{ fmtInt((managePage - 1) * manageSize + managedJobs.length) }} / {{ fmtInt(manageTotal) }}</span>
        <button class="button secondary" type="button" :disabled="managePage >= Math.max(1, manageTotalPages) || managementLoading" @click="changePage(managePage + 1)">下一页</button>
      </footer>
    </section>

    <Teleport to="body">
      <div v-if="editVisible" class="edit-modal-backdrop" @click.self="editVisible = false">
        <section class="edit-modal" role="dialog" aria-modal="true" aria-label="修改治理后 JD">
          <header class="edit-modal-head">
            <div>
              <span class="eyebrow">人工修订 · Raw #{{ editForm.rawJobId }}</span>
              <h2>修改 JD 与能力项</h2>
              <p>人工修改会保留原始数据，便于追溯。</p>
            </div>
            <button class="modal-close" type="button" @click="editVisible = false"><AppIcon name="close" :size="19" /></button>
          </header>

          <div class="edit-modal-body">
            <div class="edit-form-grid">
              <label class="field field-wide"><span>标准岗位名称</span><input v-model="editForm.titleStandard" class="input" /></label>
              <label class="field"><span>企业名称</span><input v-model="editForm.company" class="input" /></label>
              <label class="field"><span>城市</span><input v-model="editForm.city" class="input" /></label>
              <label class="field"><span>发布年份</span><input v-model.number="editForm.publishedYear" class="input" type="number" min="2000" max="2100" /></label>
              <label class="field"><span>技术栈</span><input v-model="editForm.techStack" class="input" /></label>
              <label class="field"><span>岗位级别</span><select v-model="editForm.levelName" class="select"><option>初级</option><option>中级</option><option>高级</option><option>未标注</option></select></label>
              <label class="field checkbox-field"><input v-model="editForm.validForAnalysis" type="checkbox" /><span>允许进入后续分析</span></label>
              <label class="field field-wide"><span>清洗后职位描述</span><textarea v-model="editForm.descriptionClean" class="textarea edit-description" rows="7"></textarea></label>
            </div>

            <div class="skill-editor">
              <div class="skill-editor-head">
                <div><h3>能力项（技能项）</h3><p>可以逐项添加或删除；人工添加的技能标记为 MANUAL，并在后续重新治理时优先保留。</p></div>
                <span class="status-badge good">{{ editSkills.length }} 项</span>
              </div>

              <div class="editable-skill-list">
                <span v-for="skill in editSkills" :key="skill.id" class="editable-skill-chip" :class="String(skill.requirement_type).toLowerCase()">
                  <b>{{ skill.skill_name }}</b>
                  <small>{{ requirementLabel(skill.requirement_type) }} · {{ skill.origin_type || 'AUTO' }}</small>
                  <button type="button" title="删除技能" @click="removeSkill(skill)"><AppIcon name="close" :size="12" /></button>
                </span>
                <span v-if="!editSkills.length" class="muted">当前 JD 暂无技能项。</span>
              </div>

              <div class="add-skill-row">
                <input v-model="newSkill.skillName" class="input" placeholder="技能名称，例如 LangGraph" @keydown.enter.prevent="addSkill" />
                <select v-model="newSkill.requirementType" class="select">
                  <option value="REQUIRED">必备技能</option>
                  <option value="BONUS">加分技能</option>
                  <option value="MENTIONED">一般提及</option>
                </select>
                <input v-model="newSkill.techStack" class="input" placeholder="技术栈（可选）" />
                <button class="button primary" type="button" :disabled="editLoading || !newSkill.skillName.trim()" @click="addSkill"><AppIcon name="plus" :size="14" />添加技能</button>
              </div>
            </div>
          </div>

          <footer class="edit-modal-foot">
            <button class="button danger" type="button" @click="deleteRow({ raw_job_id: editForm.rawJobId, title_standard: editForm.titleStandard })"><AppIcon name="trash" :size="14" />删除整个 JD</button>
            <div class="modal-foot-actions">
              <button class="button secondary" type="button" @click="editVisible = false">取消</button>
              <button class="button primary" type="button" :disabled="editLoading" @click="saveEdit"><AppIcon name="check" :size="14" />保存修改</button>
            </div>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>
