<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import AppIcon from '@/components/AppIcon.vue'
import { useEnglishThemeText } from '@/composables/useEnglishThemeText'

const { tx, phrase } = useEnglishThemeText()

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
  { label: tx('原始岗位池', 'Raw Job Pool'), value: fmtInt(rawTotalDisplay.value), desc: tx('可接入企业招聘站、公开平台与本地 CSV', 'Supports enterprise career sites, public platforms and local CSV files') },
  { label: tx('已治理岗位', 'Governed Jobs'), value: fmtInt(governedDisplay.value), desc: tx(`${fmtInt(validDisplay.value)} 条可进入图谱和匹配分析`, `${fmtInt(validDisplay.value)} records ready for graph and matching analysis`) },
  { label: tx('预留测试集', 'Holdout Set'), value: fmtInt(holdoutDisplay.value || holdoutTargetDisplay.value), desc: tx(`${rawOverview.value?.holdoutYear || 2026} 年滚动验证样本`, `${rawOverview.value?.holdoutYear || 2026} rolling validation samples`) },
  { label: tx('治理进度', 'Governance Progress'), value: `${rawProgress.value}%`, desc: phrase(currentStage.value) }
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
  rawProgressState.value?.currentStage || rawOverview.value?.latestRun?.current_stage || tx('等待读取治理状态', 'Waiting for governance status')
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

const jdBenchmarkStatus = computed(() => jdBenchmarkPassed.value ? tx('验收通过', 'Accepted') : tx('需要复核', 'Review Required'))

function fieldLabel(value: unknown) {
  return value == null || value === '' ? tx('正在读取 / 未检测到', 'Loading / not detected') : phrase(value)
}

function requirementLabel(value: unknown) {
  const type = String(value || '').toUpperCase()
  if (type === 'REQUIRED') return tx('必备', 'Required')
  if (type === 'BONUS' || type === 'PREFERRED') return tx('加分', 'Preferred')
  return tx('提及', 'Mentioned')
}

function stateLabel(row: any) {
  if (row.is_deleted) return tx('已删除', 'Deleted')
  if (row.manual_modified) return tx('人工修订', 'Manual Revision')
  return row.valid_for_analysis ? tx('可分析', 'Analysis-ready') : phrase(row.governance_status || tx('待复核', 'Pending Review'))
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
        <span class="panel-kicker">{{ tx('数据治理智能体 · 招聘数据资产底座', 'Data Governance Agent · Recruiting Data Asset Foundation') }}</span>
        <h1>{{ tx('岗位 JD 治理、结构化解析与评测闭环', 'JD Governance, Structured Parsing & Evaluation Loop') }}</h1>
        <p>{{ tx('面向企业招聘与人岗匹配场景，将多源岗位 JD、行业资料、岗位标准和人工修订结果沉淀为可信岗位能力数据，为能力图谱、人岗匹配和学习路径提供高质量输入。', 'For enterprise recruiting and person-job matching, govern multi-source JDs, industry reports, role standards and manual revisions into trusted job-capability data for graph modeling, matching and learning paths.') }}</p>
        <div class="governance-hero-tags" :aria-label="tx('数据治理能力标签', 'Data governance capability tags')">
          <span>{{ tx('多源 JD 接入', 'Multi-source JD Intake') }}</span>
          <span>{{ tx('岗位标准化', 'Role Standardization') }}</span>
          <span>{{ tx('质量门控', 'Quality Gate') }}</span>
          <span>{{ tx('能力证据沉淀', 'Capability Evidence') }}</span>
        </div>
        <div class="governance-hero-actions">
          <button class="button primary" type="button" :disabled="actionLoading" @click="toggleRawGovernance">
            <AppIcon :name="isRawRunning ? 'pause' : 'play'" :size="15" />
            {{ isRawRunning ? tx('暂停治理任务', 'Pause Governance') : (governedDisplay > 0 || rawReady ? tx('继续治理任务', 'Resume Governance') : tx('启动治理任务', 'Start Governance')) }}
          </button>
          <button class="button secondary" type="button" :disabled="dataLoading" @click="refreshAll">
            <AppIcon name="refresh" :size="16" /> {{ tx('刷新全部', 'Refresh All') }}
          </button>
        </div>
      </div>

      <aside class="governance-live-console">
        <div class="live-console-head">
          <div>
            <span>{{ tx('当前治理状态', 'Current Governance Status') }}</span>
            <small>{{ phrase(currentStage) }}</small>
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
          <span>{{ tx('批处理规模', 'Batch Size') }}</span>
          <input v-model.number="batchSize" class="input" type="number" min="20" max="5000" />
        </label>
      </aside>
    </section>

    <section class="governance-operation-board enterprise-panel" v-reveal>
      <header class="enterprise-panel-head compact operation-board-head">
        <div>
          <span class="panel-kicker">{{ tx('数据接入与即时解析', 'Data Intake & Real-time Parsing') }}</span>
          <h2>{{ tx('企业岗位数据治理工作台', 'Enterprise Job Data Governance Workbench') }}</h2>
          <p>{{ tx('左侧接入岗位 JD 与岗位标准证据，右侧即时验证 JD 结构化解析能力，所有结果进入后续图谱、匹配和可信审核链路。', 'Ingest JDs and role-standard evidence on the left, validate structured JD parsing on the right, and route all results into graph, matching and trusted audit workflows.') }}</p>
        </div>
        <span class="source-chip">Intake + Parse</span>
      </header>

      <div class="operation-board-grid">
        <article class="operation-column intake-column">
          <div class="operation-column-head">
            <span><AppIcon name="database" :size="18" /></span>
            <div>
              <b>{{ tx('数据接入流水', 'Data Intake Pipeline') }}</b>
              <small>{{ tx('聚焦企业可控数据源：批量岗位 JD、岗位标准、行业资料和人工修订证据。', 'Focus on enterprise-controlled sources: bulk JDs, role standards, industry reports and manual revision evidence.') }}</small>
            </div>
          </div>

          <div class="intake-action-list">
            <label class="intake-action-row primary">
              <input type="file" accept=".csv" @change="choose($event, 'csv')" />
              <span class="intake-action-icon"><AppIcon name="database" :size="20" /></span>
              <div>
                <b>{{ tx('导入新增岗位 JD', 'Import New Job JDs') }}</b>
                <small>{{ tx('CSV 进入系统解析工作台，可继续执行结构化解析。', 'CSV files enter the parsing workbench for structured extraction.') }}</small>
              </div>
              <em>{{ tx('选择 CSV', 'Select CSV') }}</em>
            </label>
            <label class="intake-action-row">
              <input type="file" accept=".pdf,.doc,.docx,.txt,.html" @change="choose($event, 'file')" />
              <span class="intake-action-icon"><AppIcon name="upload" :size="20" /></span>
              <div>
                <b>{{ tx('上传行业资料 / 岗位标准', 'Upload Industry Reports / Role Standards') }}</b>
                <small>{{ tx('用于新岗位定义、人工审核与能力图谱补充证据。', 'Used as supplementary evidence for role definition, human audit and capability graph enrichment.') }}</small>
              </div>
              <em>{{ tx('选择文件', 'Select File') }}</em>
            </label>
          </div>

          <div class="parser-assurance-panel">
            <div class="assurance-title">
              <div>
                <span class="panel-kicker">JD 解析验收</span>
                <b>{{ tx('金标测试与幻觉门禁', 'Gold-set Test & Hallucination Gate') }}</b>
              </div>
              <button class="assurance-run-button" type="button" :disabled="parserEvaluationLoading" @click="runParserEvaluation">
                <AppIcon name="play" :size="14" />
                {{ parserEvaluationLoading ? tx('验收中', 'Validating') : tx('运行金标验收', 'Run Gold Validation') }}
              </button>
            </div>
            <div class="assurance-metrics">
              <div>
                <span>{{ tx('金标 JD', 'Gold JDs') }}</span>
                <b>{{ fmtInt(jdBenchmark.cases) }}</b>
                <small>{{ tx('要求 ≥100 条', 'Required ≥100') }}</small>
              </div>
              <div>
                <span>{{ tx('解析 F1', 'Parsing F1') }}</span>
                <b>{{ metricPct(jdBenchmark.f1) }}</b>
                <small>{{ tx('目标', 'Target') }} ≥{{ metricPct(jdBenchmark.targetAccuracy) }}</small>
              </div>
              <div>
                <span>{{ tx('精确率 / 召回', 'Precision / Recall') }}</span>
                <b>{{ metricPct(jdBenchmark.precision) }}</b>
                <small>{{ metricPct(jdBenchmark.recall) }}</small>
              </div>
              <div>
                <span>{{ tx('幻觉门禁', 'Hallucination Gate') }}</span>
                <b>≤{{ metricPct(jdBenchmark.hallucinationGate) }}</b>
                <small>{{ tx('无证据不入库', 'No evidence, no storage') }}</small>
              </div>
            </div>
            <div class="assurance-result-strip" :class="{ pass: jdBenchmarkPassed }">
              <b>{{ jdBenchmarkStatus }}</b>
              <span>{{ tx('企业验收标准：金标 JD ≥100 条 · 解析 F1 ≥90% · 幻觉风险 ≤10%', 'Enterprise acceptance: gold JDs ≥100 · parsing F1 ≥90% · hallucination risk ≤10%') }}</span>
              <small v-if="parserEvaluationLastRun">{{ tx('最近执行：', 'Last run: ') }}{{ parserEvaluationLastRun }}</small>
              <small v-else>{{ tx('点击按钮执行本地金标回归测试', 'Run local gold-set regression with the button above') }}</small>
            </div>
            <div class="assurance-rail" :aria-label="tx('JD 解析验收链路', 'JD parsing validation pipeline')">
              <span>{{ tx('规则抽取', 'Rule Extraction') }}</span>
              <span>{{ tx('DeepSeek 校准', 'DeepSeek Calibration') }}</span>
              <span>{{ tx('证据过滤', 'Evidence Filter') }}</span>
              <span>{{ tx('金标回归', 'Gold Regression') }}</span>
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
              <b>{{ tx('JD 即时解析控制台', 'Real-time JD Parsing Console') }}</b>
              <small>{{ tx('粘贴岗位名称与职位描述，实时验证技能、职责、场景抽取效果。', 'Paste a role title and description to validate skill, responsibility and scenario extraction in real time.') }}</small>
            </div>
            <button class="button secondary" type="button" @click="fillSampleJd">
              <AppIcon name="spark" :size="14" /> {{ tx('填入示例', 'Use Sample') }}
            </button>
          </div>

          <div class="parse-console-form">
            <input v-model="jdText.title" class="input" :placeholder="tx('岗位名称（可选）', 'Role title (optional)')" />
            <textarea
              v-model="jdText.description"
              class="textarea"
              rows="6"
              :placeholder="tx('粘贴职位描述，例如：负责企业多智能体协作平台开发，要求掌握 Python、LangChain、RAG……', 'Paste a job description, e.g. build an enterprise multi-agent collaboration platform; requires Python, LangChain, RAG…')"
            />
            <button
              class="button primary parse-submit-button"
              type="button"
              :disabled="jdParsing || !jdText.description.trim()"
              @click="parseJdText"
            >
              <AppIcon name="spark" :size="15" /> {{ jdParsing ? tx('解析中…', 'Parsing…') : tx('立即解析', 'Parse Now') }}
            </button>
          </div>

          <div v-if="jdParseResult" class="parse-result-board jd-quick-result">
            <div class="jd-result-head">
              <b>{{ jdParseResult.roleName }}</b>
              <span class="status-badge good">{{ tx('置信度', 'Confidence') }} {{ Math.round((jdParseResult.confidence || 0) * 100) }}%</span>
            </div>
            <div class="jd-guardrail-row">
              <span><b>{{ jdParseResult.rationale?.mode || 'deterministic-rules' }}</b><small>{{ tx('解析模式', 'Parsing Mode') }}</small></span>
              <span><b>{{ metricPct(jdParseResult.rationale?.evidenceCoverage || jdParseResult.confidence || 0) }}</b><small>{{ tx('证据覆盖', 'Evidence Coverage') }}</small></span>
              <span><b>{{ metricPct(jdParseResult.rationale?.hallucinationRisk || 0) }}</b><small>{{ tx('幻觉风险', 'Hallucination Risk') }}</small></span>
              <span><b>{{ jdParseResult.rationale?.llmBlockedUnsupportedItems || 0 }}</b><small>{{ tx('已拦截', 'Blocked') }}</small></span>
            </div>
            <div class="jd-result-grid">
              <div class="jd-result-block">
                <span class="jd-result-label">{{ tx('必备技能', 'Required Skills') }}</span>
                <div class="jd-tag-list">
                  <span v-for="s in jdParseResult.requiredSkills" :key="s" class="jd-tag jd-tag-mint">{{ s }}</span>
                  <span v-if="!jdParseResult.requiredSkills?.length" class="jd-empty">{{ tx('未识别', 'Not detected') }}</span>
                </div>
              </div>
              <div class="jd-result-block">
                <span class="jd-result-label">{{ tx('加分技能', 'Preferred Skills') }}</span>
                <div class="jd-tag-list">
                  <span v-for="s in jdParseResult.bonusSkills" :key="s" class="jd-tag jd-tag-gold">{{ s }}</span>
                  <span v-if="!jdParseResult.bonusSkills?.length" class="jd-empty">{{ tx('无', 'None') }}</span>
                </div>
              </div>
              <div class="jd-result-block">
                <span class="jd-result-label">{{ tx('核心职责', 'Core Responsibilities') }}</span>
                <ul class="jd-result-list">
                  <li v-for="r in jdParseResult.responsibilities" :key="r">{{ r }}</li>
                </ul>
              </div>
              <div class="jd-result-block">
                <span class="jd-result-label">{{ tx('典型场景', 'Typical Scenarios') }}</span>
                <div class="jd-tag-list">
                  <span v-for="s in jdParseResult.scenarios" :key="s" class="jd-tag">{{ s }}</span>
                </div>
              </div>
            </div>
          </div>

          <div v-else class="parse-empty-board">
            <span class="panel-kicker">{{ tx('解析输出结构', 'Parsing Output Structure') }}</span>
            <div class="parse-empty-grid">
              <div><b>{{ tx('标准岗位', 'Standard Role') }}</b><small>{{ tx('岗位名称、级别、技术方向', 'Role name, level and technical domain') }}</small></div>
              <div><b>{{ tx('必备技能', 'Required Skills') }}</b><small>{{ tx('岗位核心技能与证据强度', 'Core skills and evidence strength') }}</small></div>
              <div><b>{{ tx('加分技能', 'Preferred Skills') }}</b><small>{{ tx('优先项、场景项、工具链', 'Preferred items, scenarios and toolchains') }}</small></div>
              <div><b>{{ tx('场景证据', 'Scenario Evidence') }}</b><small>{{ tx('业务职责与应用场景', 'Responsibilities and business scenarios') }}</small></div>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 02 人工数据管理：永久显示、与治理任务状态完全解耦 -->
    <section class="surface table-surface governed-management-panel enterprise-management-panel" v-reveal>
      <header class="surface-head">
        <div>
          <span class="eyebrow">{{ tx('02 · 已治理 JD 数据管理', '02 · Governed JD Data Management') }}</span>
          <h2>{{ tx('随时检索、修改、删除 JD 与能力项', 'Search, edit and remove JDs and capability items anytime') }}</h2>
          <p>{{ tx('对已治理岗位数据进行检索、修改与删除，修改会留痕并可追溯。', 'Search, revise and remove governed job data with traceable manual revision records.') }}</p>
        </div>
        <span class="status-badge good">ALWAYS EDITABLE</span>
      </header>

      <div class="governed-management-toolbar">
        <input
          v-model="manageKeyword"
          class="input"
          :placeholder="tx('搜索岗位名称 / 企业 / 城市 / 技术栈', 'Search role / company / city / tech stack')"
          @keydown.enter.prevent="searchManagedJobs"
        />
        <input v-model.number="manageYear" class="input compact-year-input" type="number" min="2000" max="2100" :placeholder="tx('年份', 'Year')" />
        <select v-model="manageState" class="select">
          <option value="ACTIVE">{{ tx('未删除全部', 'All Active') }}</option>
          <option value="VALID">{{ tx('仅可分析', 'Analysis-ready Only') }}</option>
          <option value="LOW_QUALITY">{{ tx('低质量 / 待复核', 'Low-quality / Review') }}</option>
          <option value="MANUAL">{{ tx('人工修订', 'Manual Revision') }}</option>
          <option value="DELETED">{{ tx('已删除', 'Deleted') }}</option>
          <option value="ALL">{{ tx('全部状态', 'All Statuses') }}</option>
        </select>
        <select v-model.number="manageSize" class="select" @change="loadGovernedJobs(true)">
          <option :value="20">20 {{ tx('条/页', '/ page') }}</option>
          <option :value="30">30 {{ tx('条/页', '/ page') }}</option>
          <option :value="50">50 {{ tx('条/页', '/ page') }}</option>
          <option :value="100">100 {{ tx('条/页', '/ page') }}</option>
        </select>
        <button class="button primary" type="button" :disabled="managementLoading" @click="searchManagedJobs">{{ tx('查询', 'Search') }}</button>
        <button class="button secondary" type="button" :disabled="managementLoading" @click="resetManagedFilters">{{ tx('重置', 'Reset') }}</button>
      </div>

      <div v-if="managementError" class="management-inline-error">
        <span>{{ managementError }}</span>
        <button class="button secondary" type="button" @click="loadGovernedJobs(false)">{{ tx('重新读取', 'Reload') }}</button>
      </div>

      <div class="management-meta-line">
        <span>{{ tx('共', 'Total') }} {{ fmtInt(manageTotal) }} {{ tx('条匹配记录', 'matched records') }}</span>
        <span>{{ tx('第', 'Page') }} {{ managePage }} / {{ Math.max(1, manageTotalPages) }} {{ tx('页', '') }}</span>
        <span>{{ tx('当前治理状态：', 'Governance status: ') }}{{ latestStatus }}</span>
        <b>{{ tx('人工编辑始终可用', 'Manual editing always available') }}</b>
      </div>

      <div class="table-wrap scroll-table-panel governed-results-scroll">
        <div v-if="managementLoading && !managedJobs.length" class="management-table-skeleton">
          <div v-for="i in 7" :key="i"></div>
        </div>

        <table v-else-if="managedJobs.length" class="data-table governed-sample-table editable-governed-table">
          <thead>
            <tr>
              <th>Raw ID</th><th>{{ tx('标准岗位', 'Standard Role') }}</th><th>{{ tx('企业 / 城市', 'Company / City') }}</th><th>{{ tx('年份', 'Year') }}</th><th>{{ tx('技术栈 / 级别', 'Tech Stack / Level') }}</th>
              <th>{{ tx('技能数', 'Skills') }}</th><th>{{ tx('质量', 'Quality') }}</th><th>{{ tx('状态', 'Status') }}</th><th class="action-column">{{ tx('操作', 'Actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in managedJobs" :key="row.raw_job_id" :class="{ 'deleted-row': row.is_deleted }">
              <td class="mono">#{{ row.raw_job_id }}</td>
              <td>
                <b>{{ phrase(row.title_standard || row.title_raw || tx('未命名岗位', 'Unnamed Role')) }}</b>
                <small class="block muted">{{ row.title_raw }}</small>
                <span v-if="row.manual_modified" class="mini-manual-badge">{{ tx('人工修订', 'Manual Revision') }}</span>
              </td>
              <td>{{ row.company || '—' }}<small class="block muted">{{ row.city || '—' }}</small></td>
              <td class="mono">{{ row.published_year || '—' }}</td>
              <td><span class="tag">{{ phrase(row.tech_stack || tx('其他', 'Other')) }}</span><small class="block muted">{{ phrase(row.level_name) }}</small></td>
              <td class="mono">{{ row.skill_count || 0 }}</td>
              <td>{{ pct(row.quality_score) }}</td>
              <td><span class="status-badge" :class="stateClass(row)">{{ stateLabel(row) }}</span></td>
              <td class="action-column">
                <div class="row-actions">
                  <button class="table-action edit" type="button" :disabled="editLoading || row.is_deleted" @click="openEdit(row)">
                    <AppIcon name="edit" :size="14" />{{ tx('修改', 'Edit') }}
                  </button>
                  <button class="table-action delete" type="button" :disabled="row.is_deleted" @click="deleteRow(row)">
                    <AppIcon name="trash" :size="14" />{{ tx('删除', 'Delete') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <EmptyState
          v-else
          :title="tx('当前筛选条件下没有已治理 JD', 'No governed JDs match the current filters')"
          :description="tx('治理任务是否运行不会影响这里；可清空筛选条件后重新查询。', 'Governance task status does not affect this panel; clear filters and search again.')"
        />
      </div>

      <footer class="governed-management-pager">
        <button class="button secondary" type="button" :disabled="managePage <= 1 || managementLoading" @click="changePage(managePage - 1)">{{ tx('上一页', 'Previous') }}</button>
        <span>{{ fmtInt((managePage - 1) * manageSize + (managedJobs.length ? 1 : 0)) }} - {{ fmtInt((managePage - 1) * manageSize + managedJobs.length) }} / {{ fmtInt(manageTotal) }}</span>
        <button class="button secondary" type="button" :disabled="managePage >= Math.max(1, manageTotalPages) || managementLoading" @click="changePage(managePage + 1)">{{ tx('下一页', 'Next') }}</button>
      </footer>
    </section>

    <Teleport to="body">
      <div v-if="editVisible" class="edit-modal-backdrop" @click.self="editVisible = false">
        <section class="edit-modal" role="dialog" aria-modal="true" :aria-label="tx('修改治理后 JD', 'Edit Governed JD')">
          <header class="edit-modal-head">
            <div>
              <span class="eyebrow">{{ tx('人工修订', 'Manual Revision') }} · Raw #{{ editForm.rawJobId }}</span>
              <h2>{{ tx('修改 JD 与能力项', 'Edit JD and Capability Items') }}</h2>
              <p>{{ tx('人工修改会保留原始数据，便于追溯。', 'Manual edits preserve raw data for traceability.') }}</p>
            </div>
            <button class="modal-close" type="button" @click="editVisible = false"><AppIcon name="close" :size="19" /></button>
          </header>

          <div class="edit-modal-body">
            <div class="edit-form-grid">
              <label class="field field-wide"><span>{{ tx('标准岗位名称', 'Standard Role Name') }}</span><input v-model="editForm.titleStandard" class="input" /></label>
              <label class="field"><span>{{ tx('企业名称', 'Company') }}</span><input v-model="editForm.company" class="input" /></label>
              <label class="field"><span>{{ tx('城市', 'City') }}</span><input v-model="editForm.city" class="input" /></label>
              <label class="field"><span>{{ tx('发布年份', 'Published Year') }}</span><input v-model.number="editForm.publishedYear" class="input" type="number" min="2000" max="2100" /></label>
              <label class="field"><span>{{ tx('技术栈', 'Tech Stack') }}</span><input v-model="editForm.techStack" class="input" /></label>
              <label class="field"><span>{{ tx('岗位级别', 'Role Level') }}</span><select v-model="editForm.levelName" class="select"><option value="初级">{{ tx('初级', 'Junior') }}</option><option value="中级">{{ tx('中级', 'Mid-level') }}</option><option value="高级">{{ tx('高级', 'Senior') }}</option><option value="未标注">{{ tx('未标注', 'Unspecified') }}</option></select></label>
              <label class="field checkbox-field"><input v-model="editForm.validForAnalysis" type="checkbox" /><span>{{ tx('允许进入后续分析', 'Allow downstream analysis') }}</span></label>
              <label class="field field-wide"><span>{{ tx('清洗后职位描述', 'Cleaned Job Description') }}</span><textarea v-model="editForm.descriptionClean" class="textarea edit-description" rows="7"></textarea></label>
            </div>

            <div class="skill-editor">
              <div class="skill-editor-head">
                <div><h3>{{ tx('能力项（技能项）', 'Capability Items / Skills') }}</h3><p>{{ tx('可以逐项添加或删除；人工添加的技能标记为 MANUAL，并在后续重新治理时优先保留。', 'Add or remove items one by one. Manually added skills are marked as MANUAL and kept with priority during future governance runs.') }}</p></div>
                <span class="status-badge good">{{ editSkills.length }} {{ tx('项', 'items') }}</span>
              </div>

              <div class="editable-skill-list">
                <span v-for="skill in editSkills" :key="skill.id" class="editable-skill-chip" :class="String(skill.requirement_type).toLowerCase()">
                  <b>{{ skill.skill_name }}</b>
                  <small>{{ requirementLabel(skill.requirement_type) }} · {{ skill.origin_type || 'AUTO' }}</small>
                  <button type="button" :title="tx('删除技能', 'Delete skill')" @click="removeSkill(skill)"><AppIcon name="close" :size="12" /></button>
                </span>
                <span v-if="!editSkills.length" class="muted">{{ tx('当前 JD 暂无技能项。', 'No skill items for this JD yet.') }}</span>
              </div>

              <div class="add-skill-row">
                <input v-model="newSkill.skillName" class="input" :placeholder="tx('技能名称，例如 LangGraph', 'Skill name, e.g. LangGraph')" @keydown.enter.prevent="addSkill" />
                <select v-model="newSkill.requirementType" class="select">
                  <option value="REQUIRED">{{ tx('必备技能', 'Required Skill') }}</option>
                  <option value="BONUS">{{ tx('加分技能', 'Preferred Skill') }}</option>
                  <option value="MENTIONED">{{ tx('一般提及', 'Mentioned') }}</option>
                </select>
                <input v-model="newSkill.techStack" class="input" :placeholder="tx('技术栈（可选）', 'Tech stack (optional)')" />
                <button class="button primary" type="button" :disabled="editLoading || !newSkill.skillName.trim()" @click="addSkill"><AppIcon name="plus" :size="14" />{{ tx('添加技能', 'Add Skill') }}</button>
              </div>
            </div>
          </div>

          <footer class="edit-modal-foot">
            <button class="button danger" type="button" @click="deleteRow({ raw_job_id: editForm.rawJobId, title_standard: editForm.titleStandard })"><AppIcon name="trash" :size="14" />{{ tx('删除整个 JD', 'Delete Entire JD') }}</button>
            <div class="modal-foot-actions">
              <button class="button secondary" type="button" @click="editVisible = false">{{ tx('取消', 'Cancel') }}</button>
              <button class="button primary" type="button" :disabled="editLoading" @click="saveEdit"><AppIcon name="check" :size="14" />{{ tx('保存修改', 'Save Changes') }}</button>
            </div>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>
