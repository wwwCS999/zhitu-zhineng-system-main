<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppIcon from '@/components/AppIcon.vue'

const governed = ref<any[]>([])
const overview = ref<any>({})
const loading = ref(false)
const error = ref('')
const url = ref('')

function fmt(value: unknown) {
  return new Intl.NumberFormat('zh-CN').format(Number(value || 0))
}

function pct(value: unknown) {
  return `${Math.round(Number(value || 0) * 100)}%`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [overviewData, rows] = await Promise.all([
      api.rawGovernanceOverview(),
      api.rawGovernanceSamples(200)
    ])
    overview.value = overviewData
    governed.value = rows as unknown as any[]
  } catch (err: any) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function collectUrl() {
  if (!url.value.trim()) return
  loading.value = true
  try {
    await api.importUrl(url.value.trim(), 'WEB')
    ElMessage.success('网页资料已进入补充数据治理流程')
    url.value = ''
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

async function choose(event: Event, type: 'csv' | 'file') {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  loading.value = true
  try {
    type === 'csv' ? await api.importCsv(file) : await api.uploadData(file, 'REPORT')
    ElMessage.success('补充数据已处理；百万训练底座仍以 MySQL 治理结果为主')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
    input.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader
      eyebrow="数据治理智能体"
      title="百万 JD 可信数据底座"
      description="治理后的岗位数据：清洗、标准化、去重与技能抽取结果"
    >
      <RouterLink to="/parsing" class="button secondary">
        <AppIcon name="scan" :size="16" /> 查看解析流水线
      </RouterLink>
      <button class="button secondary" type="button" @click="load">
        <AppIcon name="refresh" :size="16" /> 刷新
      </button>
    </PageHeader>

    <div v-if="error" class="inline-alert error"><b>百万治理数据加载失败</b><span>{{ error }}</span></div>

    <section class="metric-row compact">
      <article class="metric-card tone-mint" v-reveal>
        <span class="metric-label">原始 JD</span>
        <strong class="metric-value">{{ fmt(overview.rawTotal) }}</strong>
        <p>{{ overview.rawTable || 'dataset_job_raw' }} · 原表只读</p>
      </article>
      <article class="metric-card" v-reveal="45">
        <span class="metric-label">已治理训练 JD</span>
        <strong class="metric-value">{{ fmt(overview.governedRows) }}</strong>
        <p>不包含固定 2026 Holdout</p>
      </article>
      <article class="metric-card" v-reveal="90">
        <span class="metric-label">可用于分析</span>
        <strong class="metric-value">{{ fmt(overview.validRows) }}</strong>
        <p>{{ fmt(overview.skillRelations) }} 条技能证据关系</p>
      </article>
      <article class="metric-card tone-cream" v-reveal="135">
        <span class="metric-label">重复 / 低质量</span>
        <strong class="metric-value">{{ fmt(overview.duplicateRows) }} / {{ fmt(overview.lowQualityRows) }}</strong>
        <p>记录保留，证据分级降权</p>
      </article>
    </section>

    <section class="surface governance-status-card" v-reveal>
      <header class="surface-head">
        <div>
          <h2>治理完成状态</h2>
          <p>后续总览、探新、年度验证、能力演化和能力图谱均读取这一治理数据层。</p>
        </div>
        <span class="status-badge" :class="overview.readyForAnalysis ? 'good' : 'warn'">
          {{ overview.readyForAnalysis ? 'ANALYTICS READY' : 'GOVERNANCE INCOMPLETE' }}
        </span>
      </header>
      <div class="governance-kpis">
        <div><span>平均质量</span><b>{{ pct(overview.quality?.avg_quality) }}</b></div>
        <div><span>平均时滞风险</span><b>{{ pct(overview.quality?.avg_stale) }}</b></div>
        <div><span>2026 Holdout</span><b>{{ fmt(overview.holdoutRows) }} / {{ fmt(overview.holdoutTarget) }}</b></div>
        <div><span>剩余待治理</span><b>{{ fmt(overview.remainingRows) }}</b></div>
      </div>
    </section>

    <section class="surface table-surface" v-reveal>
      <header class="surface-head">
        <div>
          <h2>治理后岗位数据台账</h2>
          <p>下表从百万治理结果中抽取最近 200 条，可横向、纵向滚动查看。</p>
        </div>
        <span class="status-badge good">证据可追踪</span>
      </header>
      <div class="table-wrap scroll-table-panel data-governance-scroll">
        <table v-if="governed.length" class="data-table governed-sample-table">
          <thead>
            <tr>
              <th>Raw ID</th><th>标准岗位</th><th>企业 / 城市</th><th>年份</th><th>技术栈</th><th>等级</th>
              <th>技能数</th><th>质量</th><th>重复组</th><th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in governed" :key="row.raw_job_id">
              <td class="mono">#{{ row.raw_job_id }}</td>
              <td><b>{{ row.title_standard || row.title_raw || '未命名岗位' }}</b><small class="block muted">{{ row.title_raw }}</small></td>
              <td>{{ row.company || '—' }}<small class="block muted">{{ row.city || '—' }}</small></td>
              <td class="mono">{{ row.published_year || '—' }}</td>
              <td><span class="tag">{{ row.tech_stack || '其他' }}</span></td>
              <td>{{ row.level_name || '未标注' }}</td>
              <td class="mono">{{ row.skill_count || 0 }}</td>
              <td>{{ pct(row.quality_score) }}</td>
              <td class="mono muted">{{ row.duplicate_group || '—' }}</td>
              <td><span class="status-badge" :class="row.valid_for_analysis ? 'good' : 'risk'">{{ row.valid_for_analysis ? '可分析' : row.governance_status }}</span></td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-else title="暂无治理结果" description="请先在 JD 解析页面完成百万训练 JD 连续治理。" />
      </div>
    </section>

    <section class="surface supplemental-source" v-reveal>
      <header class="surface-head">
        <div><h2>补充数据接入</h2><p>保留原系统 CSV、行业资料和公开网页入口，用于后续新增证据；不会覆盖既有百万治理底座。</p></div>
      </header>
      <div class="import-grid embedded-import-grid">
        <label class="import-card primary-import">
          <input type="file" accept=".csv" @change="choose($event, 'csv')" />
          <span class="import-icon"><AppIcon name="database" :size="23" /></span>
          <div><h2>导入新增岗位 JD</h2><p>适合后续新增年度或补充平台数据。</p></div>
          <span class="button dark">选择 CSV</span>
        </label>
        <label class="import-card">
          <input type="file" accept=".pdf,.doc,.docx,.txt,.html" @change="choose($event, 'file')" />
          <span class="import-icon"><AppIcon name="upload" :size="23" /></span>
          <div><h2>上传行业资料 / 岗位标准</h2><p>作为新岗位定义与可信审核的补充证据。</p></div>
          <span class="button secondary">选择文件</span>
        </label>
      </div>
      <div class="inline-form compact-web-form">
        <div class="input-with-icon">
          <AppIcon name="link" :size="17" />
          <input v-model="url" class="input" placeholder="https://example.com/job-or-report" @keydown.enter.prevent="collectUrl" />
        </div>
        <button class="button primary" type="button" :disabled="loading || !url.trim()" @click="collectUrl">采集公开网页</button>
      </div>
    </section>
  </div>
</template>
