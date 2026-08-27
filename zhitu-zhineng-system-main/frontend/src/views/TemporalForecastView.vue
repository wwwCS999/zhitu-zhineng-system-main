<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import PageHeader from '@/components/PageHeader.vue'
import AppIcon from '@/components/AppIcon.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import EmptyState from '@/components/EmptyState.vue'

const props = withDefaults(defineProps<{
  embedded?: boolean
  discoveryTargetYear?: number | null
}>(), {
  embedded: false,
  discoveryTargetYear: null
})

const currentYear = new Date().getFullYear()

const overview = ref<any>(null)
const runs = ref<any[]>([])
const selectedRun = ref<any>(null)
const selectedCandidates = ref<any[]>([])
const loading = ref(true)
const preparing = ref(false)
const backtesting = ref(false)
const detailLoading = ref(false)
const error = ref('')

const startYear = ref(2020)
const endYear = ref(currentYear)
const topK = ref(30)
const minSupport = ref(3)
const experimentInitialized = ref(false)

const yearRows = computed(() => overview.value?.yearStats || [])
const configuredHoldoutYear = computed(() => Number(overview.value?.holdoutYear || currentYear))
const validationYear = computed(() => Number(props.discoveryTargetYear || configuredHoldoutYear.value))
const holdoutTarget = computed(() => Number(overview.value?.holdoutTarget || 1000))
const usesFixedHoldout = computed(() => validationYear.value === configuredHoldoutYear.value)
const selectedYearStat = computed(() => yearRows.value.find((item: any) => Number(item.year) === validationYear.value) || null)
const validationRows = computed(() => usesFixedHoldout.value
  ? Number(overview.value?.holdoutRows || 0)
  : Number(selectedYearStat.value?.totalRows || selectedYearStat.value?.validRows || 0))
const validationRelation = computed(() => {
  if (validationRows.value <= 0) return `${validationYear.value} 年尚无可用真实 JD，预测结果会保留，待该年度数据进入治理层后再验证。`
  return `上方预测 ${validationYear.value} 年候选，下方立即使用 ${validationYear.value} 年真实 JD 验证；切换年份时，验证窗口与结果明细同步切换。`
})
const latestBatch = computed(() => runs.value.find(item =>
  Number(item.test_year) === validationYear.value
)?.batch_id || runs.value[0]?.batch_id || '')
const latestRuns = computed(() => {
  if (!latestBatch.value) return []
  return runs.value
    .filter(item => item.batch_id === latestBatch.value && Number(item.test_year) <= validationYear.value)
    .sort((a, b) => Number(a.train_year) - Number(b.train_year))
})
const latestMean = computed(() => {
  if (!latestRuns.value.length) return { precision: 0, f1: 0, trust: 0 }
  const avg = (key: string) => latestRuns.value.reduce((sum, item) => sum + Number(item[key] || 0), 0) / latestRuns.value.length
  return {
    precision: avg('precision_score'),
    f1: avg('f1_score'),
    trust: avg('trust_score')
  }
})

function fmtInt(value: unknown) {
  return new Intl.NumberFormat('zh-CN').format(Number(value || 0))
}

function pct(value: unknown) {
  return `${Math.round(Number(value || 0) * 100)}%`
}

function scopeLabel(value: string, testYear = validationYear.value) {
  if (value === 'HOLDOUT_1000') return `${testYear} 固定 ${fmtInt(holdoutTarget.value)} 条测试集`
  if (value === 'GOVERNED_SNAPSHOT') return '当前治理快照验证'
  return '完整下一年度验证' 
}

function syncExperimentRange(year: number) {
  if (!Number.isFinite(year)) return
  const availableYears = yearRows.value
    .map((item: any) => Number(item.year))
    .filter((itemYear: number) => Number.isFinite(itemYear) && itemYear < year)
  endYear.value = year
  if (availableYears.length) startYear.value = Math.max(Math.min(...availableYears), year - 6)
  else startYear.value = year - 1
}

async function selectValidationRun(runRows = runs.value) {
  const matchedRun = runRows.find((item: any) => Number(item.test_year) === validationYear.value)
  if (matchedRun) {
    if (selectedRun.value?.run_id !== matchedRun.run_id) await openRun(matchedRun.run_id)
    return
  }
  selectedRun.value = null
  selectedCandidates.value = []
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [overviewData, runData] = await Promise.all([
      api.temporalOverview(),
      api.temporalRuns(80)
    ])
    const overviewResult: any = overviewData
    const runRows = runData as unknown as any[]
    overview.value = overviewResult
    runs.value = runRows
    if (!experimentInitialized.value) {
      syncExperimentRange(validationYear.value)
      experimentInitialized.value = true
    }
    await selectValidationRun(runRows)
  } catch (err: any) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function prepareHoldout(reset = false) {
  if (!usesFixedHoldout.value) return
  preparing.value = true
  error.value = ''
  try {
    const result: any = await api.prepareTemporalHoldout(reset, validationYear.value, holdoutTarget.value)
    ElMessage.success(`已锁定 ${result.holdoutRows} 条 ${validationYear.value} 年测试数据`)
    await loadAll()
  } catch (err: any) {
    error.value = err.message
    ElMessage.error(err.message)
  } finally {
    preparing.value = false
  }
}

async function runBacktest() {
  if (startYear.value >= endYear.value) {
    ElMessage.warning('开始年份必须早于结束年份')
    return
  }
  backtesting.value = true
  error.value = ''
  try {
    const result: any = await api.runTemporalBacktest(
      startYear.value,
      endYear.value,
      topK.value,
      minSupport.value
    )
    ElMessage.success(`完成 ${result.runs?.length || 0} 个年度窗口回测`)
    await loadAll()
  } catch (err: any) {
    error.value = err.message
    ElMessage.error(err.message)
  } finally {
    backtesting.value = false
  }
}

async function openRun(runId: string) {
  detailLoading.value = true
  try {
    const detail: any = await api.temporalRun(runId)
    selectedRun.value = detail.run
    selectedCandidates.value = detail.candidates || []
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    detailLoading.value = false
  }
}

onMounted(loadAll)

watch(() => props.discoveryTargetYear, async (year) => {
  if (!year) return
  syncExperimentRange(Number(year))
  await selectValidationRun()
})
</script>

<template>
  <div>
    <PageHeader
      v-if="!props.embedded"
      eyebrow="岗位洞察智能体 · 年度验证"
      title="新岗位预测与历史回测"
      description="年度滚动预测与回测：用 t 年岗位预测 t+1 年萌芽岗位"
    >
      <button class="button secondary" type="button" @click="loadAll">
        <AppIcon name="refresh" :size="16" />刷新
      </button>
      <button class="button primary" type="button" :disabled="preparing" @click="prepareHoldout(false)">
        <AppIcon name="target" :size="16" />{{ preparing ? '正在锁定' : `锁定 ${validationYear} 测试集` }}
      </button>
    </PageHeader>

    <div v-else class="embedded-validation-head" aria-labelledby="validation-stage-title">
      <div>
        <span class="eyebrow">目标年度真实验证</span>
        <h2 id="validation-stage-title">验证 {{ validationYear }} 年探新岗位的正确性</h2>
        <p>{{ validationRelation }}</p>
      </div>
      <div class="embedded-validation-actions">
        <button class="button secondary" type="button" @click="loadAll">
          <AppIcon name="refresh" :size="16" />刷新验证数据
        </button>
        <button v-if="usesFixedHoldout" class="button primary" type="button" :disabled="preparing" @click="prepareHoldout(false)">
          <AppIcon name="target" :size="16" />{{ preparing ? '正在锁定' : `锁定 ${validationYear} 测试集` }}
        </button>
        <span v-else class="status-badge good">使用 {{ validationYear }} 年真实 JD</span>
      </div>
    </div>

    <div v-if="error" class="inline-alert error">
      <b>年度实验暂不可用</b>
      <span>{{ error }}</span>
    </div>

    <div
      v-if="overview && !overview.analysisReady"
      class="inline-alert warn"
    >
      <b>当前快照尚不足 100 条</b>
          <span>当前已治理 {{ fmtInt(overview.governedRows) }} 条，其中 {{ fmtInt(overview.validGovernedRows) }} 条通过质量门控。至少治理 {{ fmtInt(overview.analysisMinGovernedRows || 100) }} 条后即可运行阶段性回测。</span>
    </div>

    <div
      v-else-if="overview && overview.analysisReady && !overview.fullGovernanceComplete"
      class="inline-alert warn"
    >
      <b>当前为阶段性治理快照 V{{ fmtInt(overview.snapshotVersion) }}</b>
      <span>现在可以直接运行年度回测。尚未出现在当前快照中的年份窗口会自动跳过；后续每新增约 100 条治理记录，点击“刷新”并重新回测即可更新结果。</span>
    </div>

    <div v-if="loading" class="loading-layout">
      <div v-for="index in 4" :key="index" class="skeleton-block" />
    </div>

    <template v-else-if="overview">
      <section class="metric-row compact four temporal-metrics">
        <article class="metric-card tone-mint" v-reveal>
          <span class="metric-label">历史原始岗位</span>
          <strong class="metric-value">{{ fmtInt(overview.totalRows) }}</strong>
          <p>{{ overview.rawTable }} · MySQL 原库直读</p>
        </article>
        <article class="metric-card" v-reveal="45">
          <span class="metric-label">训练池 / 已治理</span>
          <strong class="metric-value">{{ fmtInt(overview.validGovernedRows) }} / {{ fmtInt(overview.trainPoolRows) }}</strong>
          <p>分子为真正进入年度模型的有效训练 JD</p>
        </article>
        <article class="metric-card tone-cream" v-reveal="90">
          <span class="metric-label">{{ validationYear }} 验证数据</span>
          <strong class="metric-value">{{ fmtInt(validationRows) }}<template v-if="usesFixedHoldout"> / {{ fmtInt(holdoutTarget) }}</template></strong>
          <p v-if="usesFixedHoldout">{{ overview.holdoutReady ? '固定测试集，重复运行不会变化' : '尚未完成固定抽样' }}</p>
          <p v-else>使用该年度真实岗位数据跟踪预测命中情况</p>
        </article>
        <article class="metric-card" v-reveal="135">
          <span class="metric-label">发布日期索引</span>
          <strong class="metric-value small-value">{{ overview.publishedAtIndexed ? 'READY' : '建议添加' }}</strong>
          <p>百万级年度 GROUP BY 的关键性能项</p>
        </article>
      </section>

      <section class="two-column-grid temporal-top-grid">
        <article class="surface" v-reveal>
          <header class="surface-head">
            <div>
              <h2>训练 / 测试数据划分</h2>
              <p>不移动原始记录，只用 ID 清单锁定测试样本，因此不会破坏既有百万数据。</p>
            </div>
            <span class="status-badge good">无数据删除</span>
          </header>
          <div class="table-wrap compact-table-wrap">
            <table class="data-table temporal-year-table">
              <thead>
                <tr>
                  <th>年份</th>
                  <th>原始总量</th>
                  <th>原始训练池</th>
                  <th>已治理</th>
                  <th>有效训练</th>
                  <th>测试</th>
                  <th>用途</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in yearRows" :key="row.year" :class="{ 'selected-validation-year': Number(row.year) === validationYear }">
                  <td><b>{{ row.year }}</b></td>
                  <td class="mono">{{ fmtInt(row.totalRows) }}</td>
                  <td class="mono">{{ fmtInt(row.rawTrainRows) }}</td>
                  <td class="mono">{{ fmtInt(row.governedRows) }}</td>
                  <td class="mono"><b>{{ fmtInt(row.validRows) }}</b></td>
                  <td class="mono">{{ fmtInt(row.testRows) }}</td>
                  <td>
                    <span :class="['status-badge', row.testRows ? 'warn' : 'good']">
                      {{ Number(row.year) === validationYear ? '当前验证年' : row.testRows ? '治理训练 + Holdout' : '治理训练' }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <article class="surface experiment-control" v-reveal="80">
          <header class="surface-head">
            <div>
              <h2>年度滚动实验</h2>
              <p>默认滚动至 {{ validationYear - 1 }}→{{ validationYear }}，每个窗口只用下一年真实数据验证。</p>
            </div>
            <span class="status-badge warn">严格按时间切分</span>
          </header>
          <div class="form-grid temporal-form-grid">
            <label class="field">
              <span>开始训练年份</span>
              <input v-model.number="startYear" class="input" type="number" min="2000" max="2099" />
            </label>
            <label class="field">
              <span>跟随探新年份</span>
              <input :value="validationYear" class="input" type="number" readonly />
            </label>
            <label class="field">
              <span>每年预测 Top-K</span>
              <input v-model.number="topK" class="input" type="number" min="5" max="200" />
            </label>
            <label class="field">
              <span>最低训练支持数</span>
              <input v-model.number="minSupport" class="input" type="number" min="2" max="100" />
            </label>
          </div>
          <div class="experiment-logic">
            <div><span>01</span><p><b>训练</b>：仅读取年份 t 的岗位分布。</p></div>
            <div><span>02</span><p><b>预测</b>：综合新颖度、下半年动量、样本支持与企业覆盖。</p></div>
            <div><span>03</span><p><b>验证</b>：使用 {{ validationYear }} 年真实岗位{{ usesFixedHoldout ? `，并严格限定在固定的 ${fmtInt(holdoutTarget)} 条 holdout` : '，跟踪候选的真实出现与增长变化' }}。</p></div>
          </div>
          <button class="button primary wide-button" type="button" :disabled="backtesting || !overview?.analysisReady" @click="runBacktest">
            <AppIcon name="play" :size="16" />
            {{ backtesting ? '正在读取当前快照并回测…' : '更新并运行当前快照回测' }}
          </button>
        </article>
      </section>

      <section class="surface latest-result-surface" v-reveal>
        <header class="surface-head">
          <div>
            <h2>最近一轮回测结果</h2>
            <p>预测准确率使用 Precision@K；可信度同时考虑命中、概率校准、标题相似度和证据充分度。</p>
          </div>
          <span v-if="latestBatch" class="meta-number mono">Batch {{ latestBatch.slice(0, 8) }}</span>
        </header>

        <div v-if="latestRuns.length" class="backtest-summary">
          <div class="score-summary temporal-score-summary">
            <div><strong>{{ pct(latestMean.precision) }}</strong><span>平均预测准确率</span></div>
            <div><strong>{{ pct(latestMean.f1) }}</strong><span>平均 F1</span></div>
            <div><strong>{{ pct(latestMean.trust) }}</strong><span>平均可信度</span></div>
          </div>

          <div class="run-timeline">
            <button
              v-for="(run, index) in latestRuns"
              :key="run.run_id"
              type="button"
              class="run-window"
              :class="{ active: selectedRun?.run_id === run.run_id }"
              @click="openRun(run.run_id)"
              v-reveal="index * 55"
            >
              <div class="run-years">
                <span>{{ run.train_year }}</span>
                <AppIcon name="arrow" :size="15" />
                <b>{{ run.test_year }}</b>
              </div>
              <div class="run-score"><strong>{{ pct(run.precision_score) }}</strong><span>准确率</span></div>
              <ProgressBar :value="Number(run.precision_score || 0) * 100" tone="mint" />
              <div class="run-foot">
                <span>命中 {{ run.matched_count }}/{{ run.prediction_count }}</span>
                <span>可信 {{ pct(run.trust_score) }}</span>
              </div>
              <small>{{ scopeLabel(run.test_scope, Number(run.test_year)) }}</small>
            </button>
          </div>
        </div>

        <EmptyState
          v-else
          title="还没有年度回测结果"
          :description="usesFixedHoldout ? `先锁定 ${validationYear} 年 ${fmtInt(holdoutTarget)} 条测试集，再运行截至该年的滚动实验。` : `运行截至 ${validationYear} 年的年度滚动实验，即可验证该年探新结果并跟踪历年变化。`"
        />
      </section>

      <section v-if="selectedRun" class="surface candidate-detail-surface" v-reveal>
        <header class="surface-head">
          <div>
            <h2>{{ selectedRun.train_year }} → {{ selectedRun.test_year }} 预测明细</h2>
            <p>{{ scopeLabel(selectedRun.test_scope, Number(selectedRun.test_year)) }} · 点击其他年度窗口可切换并跟踪变化。</p>
          </div>
          <div class="detail-head-stats">
            <span>Precision <b>{{ pct(selectedRun.precision_score) }}</b></span>
            <span>F1 <b>{{ pct(selectedRun.f1_score) }}</b></span>
            <span>Trust <b>{{ pct(selectedRun.trust_score) }}</b></span>
          </div>
        </header>

        <div v-if="detailLoading" class="detail-loading">正在读取预测证据…</div>
        <div v-else class="table-wrap">
          <table class="data-table forecast-table">
            <thead>
              <tr>
                <th>#</th>
                <th>预测新岗位 / 萌芽岗位</th>
                <th>训练样本</th>
                <th>上年样本</th>
                <th>新颖度</th>
                <th>增长动量</th>
                <th>预测分</th>
                <th>可信度</th>
                <th>下一年真实匹配</th>
                <th>相似度</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in selectedCandidates" :key="item.rank_no">
                <td class="mono">{{ item.rank_no }}</td>
                <td><b>{{ item.predicted_title }}</b><small class="block">{{ item.normalized_title }}</small></td>
                <td class="mono">{{ fmtInt(item.train_count) }}</td>
                <td class="mono">{{ fmtInt(item.previous_count) }}</td>
                <td>{{ pct(item.novelty_score) }}</td>
                <td>{{ pct(item.momentum_score) }}</td>
                <td><b>{{ pct(item.forecast_score) }}</b></td>
                <td>{{ pct(item.confidence) }}</td>
                <td>
                  <span v-if="item.actual_title">{{ item.actual_title }}</span>
                  <span v-else class="muted">未匹配</span>
                  <small v-if="item.actual_count" class="block">估算/实际支持 {{ Math.round(Number(item.actual_count)) }}</small>
                </td>
                <td>{{ pct(item.similarity) }}</td>
                <td>
                  <span :class="['status-badge', Number(item.hit_flag) === 1 ? 'good' : 'risk']">
                    {{ Number(item.hit_flag) === 1 ? '命中' : '未命中' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

    </template>
  </div>
</template>
