<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import TrustBadge from '@/components/TrustBadge.vue'
import AppIcon from '@/components/AppIcon.vue'

const events = ref<any[]>([])
const filter = ref('ALL')
const loading = ref(false)
const selectedEventId = ref<number | null>(null)
const shown = computed(() => {
  const rows = filter.value === 'ALL' ? events.value : events.value.filter(item => item.change_type === filter.value)
  return [...rows].sort((a, b) => Math.abs(changeDelta(b)) - Math.abs(changeDelta(a)))
})
const counts = computed(() => ({
  added: events.value.filter(item => item.change_type === 'ADDED').length,
  weakened: events.value.filter(item => item.change_type === 'WEAKENED').length,
  modified: events.value.filter(item => item.change_type === 'MODIFIED').length
}))
const netMomentum = computed(() => events.value.reduce((sum, item) => sum + changeDelta(item), 0))
const upEvents = computed(() => shown.value.filter(item => changeDelta(item) > 0).slice(0, 5))
const downEvents = computed(() => shown.value.filter(item => changeDelta(item) < 0).slice(0, 5))
const selectedEvent = computed(() => {
  const rows = shown.value
  return rows.find(item => item.id === selectedEventId.value) || rows[0] || null
})
const trendSeries = computed(() => {
  const rows = [...shown.value].slice(0, 14).reverse()
  if (!rows.length) return { points: '', area: '', labels: [], rows: [] as any[] }
  let score = 50
  const points = rows.map((item, index) => {
    score = Math.max(12, Math.min(88, score + changeDelta(item) * 1.6))
    const x = rows.length === 1 ? 50 : 6 + index * (88 / (rows.length - 1))
    const y = 94 - score
    return { x, y, item, score }
  })
  const line = points.map(point => `${point.x},${point.y}`).join(' ')
  const area = `6,94 ${line} 94,94`
  const labels = points.filter((_, index) => index === 0 || index === points.length - 1 || index === Math.floor(points.length / 2))
  return { points: line, area, labels, rows }
})

async function load() {
  events.value = await api.evolutions() as unknown as any[]
  if (!selectedEventId.value && events.value.length) selectedEventId.value = events.value[0].id
}

async function analyze() {
  loading.value = true
  try {
    await api.analyzeEvolution()
    await load()
    ElMessage.success('岗位能力演化分析已完成')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

function changeLabel(type: string) {
  return type === 'ADDED' ? '新增' : type === 'WEAKENED' ? '弱化' : '修改'
}

function changeDelta(event: any) {
  const values = String(event.explanation || '').match(/\d+(?:\.\d+)?(?=%)/g)?.map(Number) || []
  const diff = values.length >= 2 ? values[values.length - 1] - values[0] : values[0] || 0
  if (event.change_type === 'WEAKENED') return -Math.max(1, Math.abs(diff || toNumber(event.confidence, 0.5) * 18))
  if (event.change_type === 'ADDED') return Math.max(1, Math.abs(diff || toNumber(event.confidence, 0.5) * 16))
  return Math.max(0.5, Math.abs(diff || toNumber(event.confidence, 0.5) * 6))
}

function toNumber(value: unknown, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function deltaText(event: any) {
  const delta = changeDelta(event)
  const sign = delta > 0 ? '+' : ''
  return `${sign}${delta.toFixed(1)}%`
}

function deltaTone(event: any) {
  if (changeDelta(event) < 0) return 'down'
  if (event.change_type === 'MODIFIED') return 'revise'
  return 'up'
}

function eventDecision(event: any) {
  if (!event) return ''
  if (event.change_type === 'WEAKENED') return '建议进入降权、替换或删除审核'
  if (event.change_type === 'MODIFIED') return '建议更新能力层级与岗位描述'
  return '建议纳入岗位能力画像并提升权重'
}

function confidencePct(event: any) {
  return Math.round(toNumber(event?.confidence, 0) * 100)
}

onMounted(load)
</script>

<template>
  <div class="evolution-product-page">
    <PageHeader
      eyebrow="能力图谱与演化智能体"
      title="岗位技能变化监控"
      description="以企业岗位能力图谱为底座，按时间线监控技能升温、弱化和要求变更，辅助岗位标准和招聘策略更新。"
    >
      <select v-model="filter" class="select compact-select">
        <option value="ALL">全部变化</option>
        <option value="ADDED">新增</option>
        <option value="WEAKENED">弱化</option>
        <option value="MODIFIED">修改</option>
      </select>
      <button class="button secondary" type="button" @click="load"><AppIcon name="refresh" :size="16" />刷新</button>
      <button class="button primary" type="button" :disabled="loading" @click="analyze"><AppIcon name="pulse" :size="16" />{{ loading ? '分析中' : '执行演化分析' }}</button>
      <RouterLink to="/audit" class="button dark"><AppIcon name="audit" :size="16" />查看待审核</RouterLink>
    </PageHeader>

    <section class="evolution-market-board" v-reveal>
      <div class="evolution-market-chart">
        <header>
          <div>
            <span class="eyebrow">技能行情趋势</span>
            <h2>岗位能力涨跌监控</h2>
            <p>以本次筛选事件构建技能净动量曲线，优先定位升温技能、弱化技能和需要修订的能力关系。</p>
          </div>
          <strong :class="netMomentum >= 0 ? 'up' : 'down'">{{ netMomentum >= 0 ? '+' : '' }}{{ netMomentum.toFixed(1) }}%</strong>
        </header>

        <div v-if="shown.length" class="skill-market-svg">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
            <defs>
              <linearGradient id="evolutionArea" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stop-color="#0f766e" stop-opacity=".24" />
                <stop offset="100%" stop-color="#0f766e" stop-opacity="0" />
              </linearGradient>
            </defs>
            <path :d="`M ${trendSeries.area} Z`" fill="url(#evolutionArea)" />
            <polyline :points="trendSeries.points" fill="none" stroke="#0f766e" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round" />
            <circle
              v-for="point in trendSeries.labels"
              :key="point.item.id"
              :cx="point.x"
              :cy="point.y"
              r="1.8"
              fill="#f59e0b"
              stroke="#fff"
              stroke-width=".8"
            />
          </svg>
          <div class="skill-market-axis">
            <span>上期岗位证据</span>
            <span>当前治理快照</span>
          </div>
        </div>
        <EmptyState v-else title="暂无演化行情" description="执行能力演化分析后，这里会生成岗位技能涨跌曲线。" />
      </div>

      <aside class="evolution-market-side">
        <div class="evolution-market-kpis">
          <article><span>新增</span><b>{{ counts.added }}</b><small>升温技能</small></article>
          <article><span>弱化</span><b>{{ counts.weakened }}</b><small>降权候选</small></article>
          <article><span>修订</span><b>{{ counts.modified }}</b><small>口径变化</small></article>
        </div>
        <div v-if="selectedEvent" class="evolution-selected-card" :class="deltaTone(selectedEvent)">
          <span>{{ changeLabel(selectedEvent.change_type) }}信号</span>
          <h3>{{ selectedEvent.role_name }} · {{ selectedEvent.skill_name }}</h3>
          <strong>{{ deltaText(selectedEvent) }}</strong>
          <p>{{ eventDecision(selectedEvent) }}</p>
          <div>
            <em>{{ selectedEvent.evidence_count }} 条证据</em>
            <em>可信 {{ confidencePct(selectedEvent) }}%</em>
          </div>
        </div>
      </aside>
    </section>

    <section class="evolution-mover-board">
      <article class="surface evolution-mover-panel" v-reveal>
        <header class="surface-head"><div><span class="eyebrow">升温榜</span><h2>需求上涨技能</h2><p>适合进入岗位标准、招聘筛选和培养路径。</p></div></header>
        <div class="evolution-mover-list">
          <button v-for="event in upEvents" :key="event.id" type="button" :class="{ active: selectedEvent?.id === event.id }" @click="selectedEventId = event.id">
            <span>{{ event.skill_name }}</span>
            <b>{{ deltaText(event) }}</b>
            <small>{{ event.role_name }}</small>
          </button>
          <EmptyState v-if="!upEvents.length" title="暂无上涨信号" description="当前筛选下没有新增或升温技能。" />
        </div>
      </article>

      <article class="surface evolution-mover-panel down" v-reveal="45">
        <header class="surface-head"><div><span class="eyebrow">降温榜</span><h2>弱化与降权技能</h2><p>适合进入人工审核，避免岗位画像滞后。</p></div></header>
        <div class="evolution-mover-list">
          <button v-for="event in downEvents" :key="event.id" type="button" :class="{ active: selectedEvent?.id === event.id }" @click="selectedEventId = event.id">
            <span>{{ event.skill_name }}</span>
            <b>{{ deltaText(event) }}</b>
            <small>{{ event.role_name }}</small>
          </button>
          <EmptyState v-if="!downEvents.length" title="暂无下降信号" description="当前筛选下没有明显弱化技能。" />
        </div>
      </article>
    </section>

    <section class="surface evolution-trade-ledger" v-reveal>
      <header class="surface-head">
        <div>
          <span class="eyebrow">变化明细</span>
          <h2>岗位技能行情台账</h2>
          <p>当前筛选 {{ shown.length }} 条变化记录，按涨跌幅和风险优先展示，支持滚动查看全部。</p>
        </div>
        <span class="status-badge good">可进入可信审核</span>
      </header>
      <div v-if="shown.length" class="evolution-trade-table">
        <article
          v-for="(event, index) in shown"
          :key="event.id"
          class="evolution-trade-row"
          :class="[deltaTone(event), { active: selectedEvent?.id === event.id }]"
          @click="selectedEventId = event.id"
          v-reveal="index * 25"
        >
          <span class="change-badge" :class="event.change_type.toLowerCase()">{{ changeLabel(event.change_type) }}</span>
          <div>
            <b>{{ event.role_name }} · {{ event.skill_name }}</b>
            <small>{{ event.old_value }} → {{ event.new_value }}</small>
          </div>
          <strong>{{ deltaText(event) }}</strong>
          <em>{{ event.evidence_count }} 条证据</em>
          <TrustBadge :score="event.confidence || 0" />
          <p>{{ event.explanation }}</p>
          <RouterLink :to="{ path: '/audit', query: { type: 'EVOLUTION', id: event.id } }" class="button secondary">
            进入审核
          </RouterLink>
        </article>
      </div>
      <EmptyState v-else title="暂无演化事件" description="执行能力演化分析后，系统会按变化类型生成技能行情台账。" />
    </section>

    <section class="evolution-governance-strip" v-reveal>
      <article>
        <span>01</span>
        <div><b>行情识别</b><p>按岗位、技能和年度证据变化计算上涨、弱化和修订信号。</p></div>
      </article>
      <article>
        <span>02</span>
        <div><b>策略联动</b><p>上涨技能进入岗位画像，弱化技能进入降权或删除审核。</p></div>
      </article>
      <article>
        <span>03</span>
        <div><b>可信发布</b><p>高影响变化进入可信审核，保留证据和人工决策留痕。</p></div>
      </article>
    </section>
  </div>
</template>
