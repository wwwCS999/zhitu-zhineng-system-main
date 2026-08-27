<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import ProgressBar from '@/components/ProgressBar.vue'

type IntakeMode = 'text' | 'file' | 'image'

const resumes = ref<any[]>([])
const roles = ref<any[]>([])
const resumeId = ref<number>()
const roleId = ref<number>()
const parseResult = ref<any>(null)
const report = ref<any>(null)
const loading = ref(false)
const parsing = ref(false)
const refreshing = ref(false)
const modelStatus = ref<any>({})
const activeIntakeMode = ref<IntakeMode>('image')
const matchPolicy = ref<'balanced' | 'strict' | 'growth'>('balanced')
const resumeText = ref(`姓名：李同学
本科，1 年开发经验。掌握 Java、Spring Boot、MySQL、Redis、Docker、Git。
参与企业知识库与微服务项目，负责接口开发、检索链路和部署联调。`)

async function load() {
  const [resumeRows, roleRows, status] = await Promise.all([
    api.resumes(),
    api.roles(),
    api.agentStatus().catch(() => null)
  ])
  resumes.value = resumeRows as unknown as any[]
  roles.value = roleRows as unknown as any[]
  if (status) modelStatus.value = status
  if (!resumeId.value && resumes.value[0]) resumeId.value = resumes.value[0].id
  if (!roleId.value && roles.value[0]) roleId.value = roles.value[0].id
}

function isImageResumeFile(file: File) {
  return file.type.startsWith('image/') || /\.(png|jpe?g|webp|bmp)$/i.test(file.name)
}

function parseResumeErrorMessage(err: any) {
  const message = String(err?.message || '解析失败，请稍后重试')
  if (activeIntakeMode.value === 'image' || /图片|image|OCR|VISION|视觉/i.test(message)) {
    return `${message}。请确认已配置支持图片输入的视觉模型，或上传清晰原图。`
  }
  return message
}

async function refreshSnapshotRoles() {
  refreshing.value = true
  try {
    await load()
    ElMessage.success('已同步候选人画像与岗位目录')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    refreshing.value = false
  }
}

async function parseUploadedFile(file: File) {
  parsing.value = true
  try {
    if (isImageResumeFile(file)) activeIntakeMode.value = 'image'
    const result: any = isImageResumeFile(file)
      ? await api.parseResumeImage(file)
      : await api.parseResume(file)
    parseResult.value = result
    await load()
    resumeId.value = result.resumeId
    ElMessage.success(`解析完成：${result.extraction?.personName || '候选人'}，完整率 ${result.metrics?.parseRate || 0}%`)
  } catch (err: any) {
    ElMessage.error(parseResumeErrorMessage(err))
  } finally {
    parsing.value = false
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  await parseUploadedFile(file)
  input.value = ''
}

async function parseText() {
  if (!resumeText.value.trim()) {
    ElMessage.warning('请先粘贴简历文本')
    return
  }
  parsing.value = true
  try {
    const result: any = await api.parseResumeText(resumeText.value)
    parseResult.value = result
    await load()
    resumeId.value = result.resumeId
    ElMessage.success(`文本解析完成，完整率 ${result.metrics?.parseRate || 0}%`)
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    parsing.value = false
  }
}

async function match() {
  if (!resumeId.value || !roleId.value) {
    ElMessage.warning('请选择候选人画像和目标岗位')
    return
  }
  loading.value = true
  try {
    report.value = await api.analyzeMatch(resumeId.value, roleId.value)
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

function parsePercent(item: any) {
  return Math.round(Number(item?.parse_confidence || 0) * 100)
}

function storedList(value: any) {
  if (Array.isArray(value)) return value
  if (typeof value !== 'string' || !value.trim()) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function storedProfileResult(item: any) {
  if (!item) return null
  const details = item.details || {
    educationBackground: storedList(item.education_detail),
    internships: storedList(item.internships),
    projectDetails: storedList(item.project_detail)
  }
  const extraction = {
    personName: item.person_name || '候选人',
    skills: storedList(item.skills),
    projects: storedList(item.projects),
    education: item.education || '未识别',
    experienceYears: item.experience_years ?? 0,
    details
  }
  return {
    resumeId: item.id,
    extraction,
    metrics: {
      parseRate: parsePercent(item),
      qualityLevel: parsePercent(item) >= 90 ? 'HIGH' : parsePercent(item) >= 75 ? 'MEDIUM' : 'REVIEW',
      parserVersion: item.parser_version || 'resume-parser',
      extractionMode: '已保存画像',
      projectCount: extraction.projects.length,
      skillCount: extraction.skills.length,
      qualityIssues: []
    }
  }
}

const selectedResume = computed(() => resumes.value.find(item => item.id === resumeId.value))
const selectedRole = computed(() => roles.value.find(item => item.id === roleId.value))
const profileResult = computed(() => {
  const stored = storedProfileResult(selectedResume.value)
  const realtime = parseResult.value && (!resumeId.value || parseResult.value.resumeId === resumeId.value)
    ? parseResult.value
    : null
  if (!realtime) return stored
  if (!stored) return realtime
  const realtimeScore = Number(realtime.metrics?.parseRate || 0) + (realtime.extraction?.projects?.length || 0) * 8
  const storedScore = Number(stored.metrics?.parseRate || 0) + (stored.extraction?.projects?.length || 0) * 8
  return realtimeScore >= storedScore ? realtime : stored
})

const modeCards = [
  { id: 'text', title: '普通模式', desc: '粘贴文本快速验证字段抽取', icon: 'file', speed: '400-700ms' },
  { id: 'file', title: '极速模式', desc: 'PDF / Word / TXT 批量沉淀', icon: 'upload', speed: '文档解析' },
  { id: 'image', title: '精准模式', desc: '多模态理解图片版面结构', icon: 'image', speed: '视觉模型' }
] as const

const activeModeHint = computed(() => {
  if (activeIntakeMode.value === 'image') {
    return {
      title: '点击或拖拽，上传简历图片',
      desc: '支持 PNG / JPG / JPEG / WEBP / BMP，系统先读版面再做结构化校验。',
      accept: 'image/png,image/jpeg,image/jpg,image/webp,image/bmp'
    }
  }
  return {
    title: '点击或拖拽，上传简历文件',
    desc: '支持 PDF / Word / TXT，单个文件不超过 20MB。',
    accept: '.pdf,.doc,.docx,.txt'
  }
})

const visionRuntime = computed(() => ({
  enabled: Boolean(modelStatus.value?.visionImageCapable),
  model: modelStatus.value?.visionModel || '未配置',
  textModel: modelStatus.value?.model || 'deepseek-chat'
}))

const projectEvidence = computed(() => profileResult.value?.extraction?.details?.projectDetails || [])
const experienceEvidence = computed(() => profileResult.value?.extraction?.details?.internships || [])
const educationEvidence = computed(() => profileResult.value?.extraction?.details?.educationBackground || [])
const skills = computed(() => profileResult.value?.extraction?.skills || [])
const parseRate = computed(() => Number(profileResult.value?.metrics?.parseRate || 0))

const outputCards = computed(() => [
  { label: '技能标签', value: `${skills.value.length} 项`, desc: '用于岗位能力覆盖判断', icon: 'spark' },
  { label: '项目证据', value: `${projectEvidence.value.length} 个`, desc: '只统计显式项目区块', icon: 'briefcase' },
  { label: '工作/实习', value: `${experienceEvidence.value.length} 段`, desc: '按公司、岗位、时间沉淀', icon: 'pulse' },
  { label: '学历背景', value: profileResult.value?.extraction?.education || '待解析', desc: '用于门槛条件校验', icon: 'book' }
])

const hasProjectBoundaryRisk = computed(() => {
  const issues = profileResult.value?.metrics?.qualityIssues || []
  return issues.some((item: string) => /项目|证据/.test(item))
})

const acceptanceChecks = computed(() => [
  { label: '字段完整率', value: `${parseRate.value}%`, ok: parseRate.value >= 90 },
  { label: '项目边界', value: `${projectEvidence.value.length} 个`, ok: Boolean(profileResult.value) && !hasProjectBoundaryRisk.value },
  { label: '经历证据', value: `${experienceEvidence.value.length} 段`, ok: experienceEvidence.value.length > 0 || Boolean(profileResult.value) },
  { label: '学历证据', value: `${educationEvidence.value.length} 条`, ok: educationEvidence.value.length > 0 }
])

const ledgerRows = computed(() => {
  const rows: any[] = []
  projectEvidence.value.forEach((item: any, index: number) => rows.push({
    id: `p-${index}`,
    type: '项目',
    title: item.name || `项目 ${index + 1}`,
    period: item.period || item.source || '项目区块',
    evidence: item.evidence || item.description || '已结构化',
    tone: 'blue'
  }))
  experienceEvidence.value.forEach((item: any, index: number) => rows.push({
    id: `e-${index}`,
    type: '经历',
    title: [item.company, item.role].filter(Boolean).join(' · ') || `经历 ${index + 1}`,
    period: item.period || '时间待确认',
    evidence: item.evidence || item.description || '已结构化',
    tone: 'green'
  }))
  educationEvidence.value.forEach((item: any, index: number) => rows.push({
    id: `edu-${index}`,
    type: '学历',
    title: [item.school, item.major].filter(Boolean).join(' · ') || `学历 ${index + 1}`,
    period: item.period || item.degree || '学历区块',
    evidence: item.evidence || item.degree || '已结构化',
    tone: 'mint'
  }))
  return rows.slice(0, 8)
})

const readinessPercent = computed(() => {
  const checks = [Boolean(resumeId.value), Boolean(roleId.value), parseRate.value >= 75]
  return Math.round((checks.filter(Boolean).length / checks.length) * 100)
})

const targetSetupMetrics = computed(() => [
  { label: '画像完整率', value: profileResult.value ? `${parseRate.value}%` : '待解析', desc: '候选人字段质量' },
  { label: '项目证据', value: `${projectEvidence.value.length} 个`, desc: '匹配解释来源' },
  { label: '目标岗位', value: selectedRole.value?.level_name || '待选择', desc: selectedRole.value?.tech_stack || '技术方向待确认' }
])

const matchPolicies = [
  { id: 'balanced', label: '标准筛选', desc: '兼顾技能、项目、经历和学历，适合常规初筛。' },
  { id: 'strict', label: '严格技能', desc: '优先看必备技能和技术栈，适合交付压力高的岗位。' },
  { id: 'growth', label: '培养潜力', desc: '关注学习路径和转岗价值，适合储备人才。' }
] as const
const selectedPolicy = computed(() => matchPolicies.find(item => item.id === matchPolicy.value) || matchPolicies[0])

function decisionLevel(score: unknown) {
  const value = Number(score || 0)
  if (value >= 85) return '优先推进'
  if (value >= 70) return '可进入面试'
  if (value >= 55) return '进入培养池'
  return '暂不推荐'
}

const dimensionRows = computed(() => Object.entries(report.value?.dimensions || {}).map(([label, raw]) => ({
  label,
  value: Number(raw || 0),
  status: Number(raw || 0) >= 80 ? '优势' : Number(raw || 0) >= 65 ? '可用' : '待补齐'
})))

const matchedSkills = computed(() => report.value?.matchedSkills || [])
const missingSkills = computed(() => report.value?.missingSkills || [])
const candidateName = computed(() => selectedResume.value?.person_name || '待选择候选人')
const targetRoleName = computed(() => selectedRole.value?.role_name || '待选择目标岗位')
const targetLevelName = computed(() => selectedRole.value?.level_name || '岗位等级待确认')
const targetTechStack = computed(() => selectedRole.value?.tech_stack || '技术方向待确认')
const selectedModeTitle = computed(() => modeCards.find(item => item.id === activeIntakeMode.value)?.title || '解析通道')

const kpiMetrics = computed(() => [
  { label: '画像完整率', value: `${parseRate.value}%`, desc: profileResult.value ? '候选人字段抽取质量' : '解析简历后自动更新' },
  { label: '技能标签', value: `${skills.value.length} 项`, desc: '用于岗位能力覆盖判断' },
  { label: '项目证据', value: `${projectEvidence.value.length} 个`, desc: '匹配解释的可追溯来源' },
  { label: '匹配准备度', value: `${readinessPercent.value}%`, desc: '画像 + 岗位 + 完整率就绪' }
])

const decisionTone = computed(() => {
  const score = Number(report.value?.overall_score || 0)
  if (score >= 85) return 'excellent'
  if (score >= 70) return 'good'
  return 'watch'
})

const interviewActions = computed(() => {
  const items: Array<{ span: string; b: string; p: string }> = []
  dimensionRows.value.filter(row => row.value < 70).slice(0, 2).forEach((row, index) => {
    items.push({
      span: `维度核验 0${index + 1}`,
      b: `深挖「${row.label}」短板`,
      p: '要求候选人结合项目数据说明当前水平，评估差距是否可在短期补齐。'
    })
  })
  missingSkills.value.slice(0, 2).forEach((skill: string, index: number) => {
    items.push({
      span: `技能核验 0${index + 1}`,
      b: `核验「${skill}」落地能力`,
      p: '询问最近一次使用该技能的项目场景、结果与复盘，避免简历话术。'
    })
  })
  if (!items.length) {
    items.push({ span: '建议', b: '综合匹配通过', p: '未发现明显短板，可进入面试环节做软素质核验。' })
  }
  return items.slice(0, 3)
})

function riskHintForSkill(index: number) {
  const hints = [
    '面试重点核验该项能力是否真实落地。',
    '要求候选人用项目经历说明该技能的深度。',
    '关注是否存在课程学习但缺少真实交付的场景。',
    '核验该项技能与目标岗位的实际匹配度。',
    '评估是否可通过短期任务补齐到可用水平。',
    '结合岗位证据判断该技能是否为核心准入项。'
  ]
  return hints[index % hints.length]
}

onMounted(load)
</script>

<template>
  <div class="match-product-page">
    <section class="match-command-center" v-reveal>
      <div class="match-command-copy">
        <span class="match-kicker">画像匹配智能体 · 简历解析与人岗匹配</span>
        <h1>人岗匹配诊断工作台</h1>
        <p>解析候选人简历，沉淀项目、经历与学历证据，结合岗位能力图谱生成可解释的匹配诊断报告。</p>
        <div class="match-command-actions">
          <button class="button primary" type="button" :disabled="loading" @click="match">
            <AppIcon name="match" :size="16" />{{ loading ? '诊断中' : '生成匹配报告' }}
          </button>
          <button class="button secondary" type="button" :disabled="refreshing" @click="refreshSnapshotRoles">
            <AppIcon name="refresh" :size="16" />{{ refreshing ? '刷新中' : '刷新画像库' }}
          </button>
        </div>
      </div>

      <aside class="match-readiness-card">
        <div class="match-readiness-head">
          <span>当前匹配对象</span>
          <b>{{ candidateName }}</b>
        </div>
        <div class="match-route-line">
          <span>{{ candidateName }}</span>
          <AppIcon name="arrow" :size="15" />
          <span>{{ targetRoleName }}</span>
        </div>
        <div class="match-readiness-meter">
          <div>
            <span>匹配准备度</span>
            <strong>{{ readinessPercent }}%</strong>
          </div>
          <ProgressBar :value="readinessPercent" :tone="readinessPercent >= 75 ? 'mint' : 'gold'" />
        </div>
        <div class="match-ready-list">
          <span :class="{ done: Boolean(resumeId) }"><AppIcon name="check" :size="13" />候选人画像</span>
          <span :class="{ done: Boolean(roleId) }"><AppIcon name="check" :size="13" />目标岗位</span>
          <span :class="{ done: parseRate >= 75 }"><AppIcon name="check" :size="13" />完整率 ≥75%</span>
        </div>
      </aside>
    </section>

    <section class="matching-kpi-strip" v-reveal="40">
      <article v-for="item in kpiMetrics" :key="item.label">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.desc }}</small>
      </article>
    </section>

    <section class="match-ops-grid">
      <div class="match-profile-panel surface" v-reveal="80">
        <header class="match-panel-head">
          <div>
            <span class="match-kicker">简历解析</span>
            <h2>候选人画像解析</h2>
            <p>选择解析通道，秒级抽取结构化字段，并把项目、经历、学历证据沉淀到人岗匹配链路。</p>
          </div>
          <span class="status-badge" :class="visionRuntime.enabled ? 'good' : 'warn'">
            {{ visionRuntime.enabled ? '视觉模型已启用' : '视觉模型未配置' }}
          </span>
        </header>

        <div class="profile-intake-workbench">
          <aside class="profile-source-panel">
            <button
              v-for="item in modeCards"
              :key="item.id"
              type="button"
              class="profile-source-card"
              :class="{ active: activeIntakeMode === item.id }"
              @click="activeIntakeMode = item.id"
            >
              <span><AppIcon :name="item.icon" :size="18" /></span>
              <b>{{ item.title }}</b>
              <small>{{ item.desc }} · {{ item.speed }}</small>
            </button>
            <div class="profile-source-note">
              <b>证据门禁</b>
              <span>项目、经历、学历只统计显式区块，每条保留可追溯来源。</span>
            </div>
          </aside>

          <div class="profile-compose-panel">
            <header class="profile-compose-head">
              <div>
                <span class="eyebrow">{{ selectedModeTitle }}</span>
                <h3>{{ activeModeHint.title }}</h3>
              </div>
              <span class="status-badge" :class="visionRuntime.enabled ? 'good' : 'warn'">
                {{ visionRuntime.enabled ? '视觉已启用' : 'OCR 兜底' }}
              </span>
            </header>

            <section v-if="activeIntakeMode === 'text'" class="profile-text-parser">
              <textarea v-model="resumeText" class="textarea" rows="8" />
              <div class="profile-parser-actions">
                <span>粘贴简历文本，快速验证字段抽取质量。</span>
                <button class="button primary" type="button" :disabled="parsing" @click="parseText">
                  <AppIcon name="scan" :size="15" />{{ parsing ? '解析中' : '解析文本' }}
                </button>
              </div>
            </section>

            <label v-else class="profile-file-drop" :class="{ busy: parsing, 'image-mode': activeIntakeMode === 'image' }">
              <input
                type="file"
                :accept="activeModeHint.accept"
                :disabled="parsing"
                @change="upload"
              />
              <span class="match-zone-icon"><AppIcon :name="activeIntakeMode === 'image' ? 'image' : 'upload'" :size="22" /></span>
              <b>{{ parsing ? '正在解析简历' : activeModeHint.title }}</b>
              <small>{{ activeModeHint.desc }}</small>
            </label>

            <div v-if="activeIntakeMode === 'image'" class="profile-image-pipeline">
              <div><span>STEP 01</span><b>版面读取</b><small>OCR 还原文本区块</small></div>
              <div><span>STEP 02</span><b>视觉理解</b><small>Qwen-VL 结构校准</small></div>
              <div><span>STEP 03</span><b>证据校验</b><small>DeepSeek 门禁复核</small></div>
            </div>

            <div class="profile-vision-runtime">
              <div class="profile-vision-runtime-main">
                <span>文本模型</span>
                <b>{{ visionRuntime.textModel }}</b>
                <p>{{ visionRuntime.enabled ? '视觉模型已接入，图片简历走多模态理解。' : '未配置视觉模型，图片简历自动走 OCR 兜底。' }}</p>
              </div>
              <div class="profile-vision-runtime-model">
                <span>视觉模型</span>
                <b>{{ visionRuntime.model }}</b>
              </div>
            </div>
          </div>

          <aside class="profile-output-panel">
            <header>
              <b>画像输出概览</b>
              <span class="status-badge" :class="parseRate >= 75 ? 'good' : 'warn'">{{ parseRate }}% 完整率</span>
            </header>
            <div class="profile-output-list">
              <div v-for="item in outputCards" :key="item.label">
                <span><AppIcon :name="item.icon" :size="16" /></span>
                <div>
                  <b>{{ item.label }}</b>
                  <strong>{{ item.value }}</strong>
                  <small>{{ item.desc }}</small>
                </div>
              </div>
            </div>
            <div class="profile-standby-checks">
              <span v-for="item in acceptanceChecks" :key="item.label" :class="{ done: item.ok }">
                <AppIcon :name="item.ok ? 'check' : 'focus'" :size="12" />{{ item.label }} · {{ item.value }}
              </span>
            </div>
          </aside>
        </div>

        <div v-if="profileResult" class="parse-profile-board enterprise-result-board" v-reveal>
          <div class="parse-result-identity">
            <div class="parse-profile-score" :class="{ excellent: parseRate >= 90 }">
              <strong>{{ parseRate }}%</strong>
              <span>完整率</span>
            </div>
            <div class="parse-profile-person">
              <span class="status-badge good">{{ profileResult.metrics?.extractionMode || '企业解析链路' }}</span>
              <h3>{{ profileResult.extraction?.personName || '候选人' }}</h3>
              <div class="parse-profile-facts">
                <span>{{ profileResult.extraction?.education || '学历待确认' }}</span>
                <span>{{ profileResult.extraction?.experienceYears ?? 0 }} 年经验</span>
                <span>{{ skills.length }} 项技能</span>
                <span>{{ projectEvidence.length }} 个项目</span>
              </div>
            </div>
          </div>

          <div class="parse-result-evidence">
            <header>
              <div>
                <span class="eyebrow">解析结果</span>
                <b>关键经历证据</b>
              </div>
              <span class="status-badge" :class="parseRate >= 90 ? 'good' : 'warn'">
                {{ parseRate >= 90 ? '达到验收线' : '建议复核' }}
              </span>
            </header>
            <div class="parse-evidence-list">
              <article v-for="item in ledgerRows.slice(0, 5)" :key="item.id" :class="`tone-${item.tone}`">
                <span>{{ item.type }}</span>
                <div>
                  <b>{{ item.title }}</b>
                  <small>{{ item.period }}</small>
                  <p>{{ item.evidence }}</p>
                </div>
              </article>
              <article v-if="!ledgerRows.length" class="empty">
                <span>待补充</span>
                <div>
                  <b>暂无可展开证据</b>
                  <small>上传清晰简历后自动生成</small>
                  <p>系统会优先展示项目、工作/实习和学历三类结构化证据。</p>
                </div>
              </article>
            </div>
          </div>

          <aside class="parse-result-side">
            <div class="parse-result-mini-grid">
              <div>
                <span>项目</span>
                <b>{{ projectEvidence.length }}</b>
              </div>
              <div>
                <span>经历</span>
                <b>{{ experienceEvidence.length }}</b>
              </div>
              <div>
                <span>学历</span>
                <b>{{ educationEvidence.length }}</b>
              </div>
            </div>
            <div class="parse-result-skills">
              <span class="eyebrow">核心技能</span>
              <div class="skill-cloud compact">
                <span v-for="skill in skills.slice(0, 12)" :key="skill" class="tag blue">{{ skill }}</span>
                <span v-if="!skills.length" class="tag">待解析</span>
              </div>
            </div>
          </aside>
        </div>

        <div v-if="false && profileResult" class="parse-profile-board" v-reveal>
          <div class="parse-profile-score" :class="{ excellent: parseRate >= 90 }">
            <strong>{{ parseRate }}%</strong>
            <span>完整率</span>
          </div>
          <div>
            <div class="parse-profile-top">
              <div>
                <span class="status-badge good">{{ profileResult.metrics?.extractionMode || '企业解析链路' }}</span>
                <h3>{{ profileResult.extraction?.personName || '候选人' }}</h3>
              </div>
              <span class="status-badge" :class="parseRate >= 90 ? 'good' : 'warn'">企业验收线</span>
            </div>
            <div class="parse-profile-facts">
              <span>{{ profileResult.extraction?.education || '学历待确认' }}</span>
              <span>{{ profileResult.extraction?.experienceYears ?? 0 }} 年经验</span>
              <span>{{ skills.length }} 项技能</span>
              <span>{{ projectEvidence.length }} 个项目</span>
              <span>{{ experienceEvidence.length }} 段经历</span>
              <span>{{ educationEvidence.length }} 条学历</span>
            </div>
            <div class="skill-cloud">
              <span v-for="skill in skills.slice(0, 20)" :key="skill" class="tag blue">{{ skill }}</span>
            </div>
          </div>
        </div>

        <div v-if="!profileResult" class="profile-standby-board" v-reveal>
          <div class="profile-standby-hero">
            <div>
              <span class="eyebrow">待解析画像</span>
              <h3>上传简历，生成候选人画像报告</h3>
              <p>系统抽取技能、项目、经历和学历证据，并输出完整率与验收建议。</p>
            </div>
            <div class="profile-standby-state">
              <b>{{ parseRate ? `${parseRate}%` : '--' }}</b>
              <span>完整率</span>
            </div>
          </div>
          <div class="profile-standby-metrics">
            <div v-for="item in outputCards.slice(0, 3)" :key="item.label">
              <span>{{ item.label }}</span>
              <b>{{ item.value }}</b>
              <small>{{ item.desc }}</small>
            </div>
          </div>
          <div class="profile-standby-grid">
            <div class="profile-standby-card">
              <header><b>企业验收线</b></header>
              <div class="profile-standby-checks">
                <span v-for="item in acceptanceChecks" :key="item.label" :class="{ done: item.ok }">
                  <AppIcon :name="item.ok ? 'check' : 'focus'" :size="12" />{{ item.label }} · {{ item.value }}
                </span>
              </div>
            </div>
            <div class="profile-standby-card">
              <header><b>解析链路</b></header>
              <div class="profile-standby-evidence">
                <div><span>普通模式</span><b>400-700ms 文本抽取</b></div>
                <div><span>极速模式</span><b>PDF / Word / TXT 批量</b></div>
                <div><span>精准模式</span><b>多模态版面理解</b></div>
                <div><span>证据门禁</span><b>每类字段保留来源</b></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <aside class="match-target-panel surface" v-reveal="120">
        <header class="match-panel-head compact">
          <div>
            <span class="match-kicker">匹配目标</span>
            <h2>锁定候选人与岗位</h2>
            <p>选择候选人画像与标准岗位画像，生成可解释的招聘筛选结论。</p>
          </div>
        </header>

        <div class="match-lock-stack">
          <div class="match-lock-card">
            <div class="match-lock-head">
              <span>匹配准备度</span>
              <b>{{ readinessPercent }}%</b>
            </div>
            <ProgressBar :value="readinessPercent" :tone="readinessPercent >= 75 ? 'mint' : 'gold'" />
            <div class="match-lock-route">
              <div>
                <small>候选人</small>
                <b>{{ candidateName }}</b>
                <span>{{ selectedResume?.education || '学历待确认' }}</span>
              </div>
              <AppIcon name="arrow" :size="16" />
              <div>
                <small>目标岗位</small>
                <b>{{ targetRoleName }}</b>
                <span>{{ targetLevelName }} · {{ targetTechStack }}</span>
              </div>
            </div>
            <div class="match-ready-pills">
              <span :class="{ done: Boolean(resumeId) }"><AppIcon name="check" :size="11" />候选人画像</span>
              <span :class="{ done: Boolean(roleId) }"><AppIcon name="check" :size="11" />目标岗位</span>
              <span :class="{ done: parseRate >= 75 }"><AppIcon name="check" :size="11" />完整率 ≥75%</span>
            </div>
          </div>

          <div class="target-selector-stack">
            <label class="field">
              <span>候选人画像</span>
              <select v-model="resumeId" class="select">
                <option v-for="item in resumes" :key="item.id" :value="item.id">{{ item.person_name }} · {{ item.education }} · 解析 {{ parsePercent(item) }}%</option>
              </select>
            </label>
            <label class="field">
              <span>目标岗位</span>
              <select v-model="roleId" class="select">
                <option v-for="item in roles" :key="item.id" :value="item.id">{{ item.role_name }} · {{ item.level_name }}</option>
              </select>
            </label>
          </div>

          <div class="match-target-metrics">
            <div v-for="item in targetSetupMetrics" :key="item.label">
              <span>{{ item.label }}</span>
              <b>{{ item.value }}</b>
            </div>
          </div>

          <div class="match-policy-panel">
            <header>
              <div>
                <span class="eyebrow">匹配策略</span>
                <b>{{ selectedPolicy.label }}</b>
              </div>
            </header>
            <div class="match-policy-list">
              <button
                v-for="item in matchPolicies"
                :key="item.id"
                type="button"
                :class="{ active: matchPolicy === item.id }"
                @click="matchPolicy = item.id"
              >
                <b>{{ item.label }}</b>
                <span>{{ item.desc }}</span>
              </button>
            </div>
          </div>

          <button class="button primary full-width" type="button" :disabled="loading" @click="match">
            <AppIcon name="match" :size="16" />{{ loading ? '诊断中' : '生成匹配报告' }}
          </button>
          <button class="button secondary full-width" type="button" :disabled="refreshing" @click="refreshSnapshotRoles">
            <AppIcon name="refresh" :size="16" />{{ refreshing ? '刷新中' : '刷新画像库' }}
          </button>

          <div v-if="!report" class="match-target-next-step">
            <span class="eyebrow">诊断待生成</span>
            <b>完成画像与岗位锁定后生成报告</b>
            <p>报告将汇总匹配度、技能覆盖、项目证据、能力缺口和面试核验建议。</p>
            <div>
              <span :class="{ done: Boolean(resumeId) }"><AppIcon name="check" :size="11" />画像</span>
              <span :class="{ done: Boolean(roleId) }"><AppIcon name="check" :size="11" />岗位</span>
              <span :class="{ done: parseRate >= 75 }"><AppIcon name="check" :size="11" />完整率</span>
            </div>
          </div>
        </div>
      </aside>
    </section>

    <section v-if="report" class="match-result-shell surface" v-reveal>
      <header class="match-report-hero">
        <div class="match-report-copy">
          <span class="match-kicker">匹配诊断报告</span>
          <h2>{{ report.person_name }} → {{ report.role_name }}</h2>
          <p>{{ report.explanation }}</p>
          <div class="match-report-route">
            <span>{{ candidateName }}</span>
            <span>{{ targetRoleName }}</span>
            <span>{{ targetLevelName }}</span>
            <span>{{ selectedPolicy.label }}</span>
          </div>
        </div>
        <aside class="match-decision-card" :class="decisionTone">
          <div>
            <span>招聘建议</span>
            <em>{{ decisionLevel(report.overall_score) }}</em>
          </div>
          <strong>{{ report.overall_score }}%</strong>
          <b>{{ decisionLevel(report.overall_score) }}</b>
          <p>结合技能覆盖、项目证据和五维匹配给出用人方向建议。</p>
        </aside>
      </header>

      <div class="match-result-cards">
        <article class="score">
          <span>综合匹配度</span>
          <b>{{ report.overall_score }}%</b>
          <small>{{ decisionLevel(report.overall_score) }}</small>
        </article>
        <article>
          <span>已覆盖技能</span>
          <b>{{ matchedSkills.length }} 项</b>
          <small>来自简历证据</small>
        </article>
        <article :class="{ warn: missingSkills.length > 0 }">
          <span>待补齐技能</span>
          <b>{{ missingSkills.length }} 项</b>
          <small>用于面试核验</small>
        </article>
        <article>
          <span>匹配策略</span>
          <b>{{ selectedPolicy.label }}</b>
          <small>{{ selectedPolicy.desc }}</small>
        </article>
      </div>

      <div class="match-report-body-grid">
        <div class="match-weight-panel">
          <header>
            <h3>五维匹配</h3>
            <small>技能 / 项目 / 经历 / 学历 / 潜力 加权归一</small>
          </header>
          <div class="match-weight-list">
            <div v-for="item in dimensionRows" :key="item.label" class="match-weight-row" :class="{ warn: item.value < 70 }">
              <div class="match-weight-main">
                <div>
                  <b>{{ item.label }}</b>
                  <span>{{ item.status }}</span>
                </div>
                <strong>{{ item.value }}%</strong>
              </div>
              <ProgressBar :value="item.value" :tone="item.value >= 70 ? 'mint' : 'gold'" />
            </div>
          </div>
        </div>

        <aside class="match-risk-panel">
          <header>
            <h3>能力缺口</h3>
            <span>{{ missingSkills.length }} 项待补齐</span>
          </header>
          <div class="match-risk-list">
            <div v-for="(skill, index) in missingSkills.slice(0, 6)" :key="skill" class="danger">
              <b>{{ skill }}</b>
              <span>Gap</span>
              <p>{{ riskHintForSkill(index) }}</p>
            </div>
            <div v-if="!missingSkills.length" class="good">
              <b>无硬性缺口</b>
              <span>OK</span>
              <p>已覆盖岗位必备技能，可进入面试核验环节。</p>
            </div>
          </div>
        </aside>
      </div>

      <div class="match-evidence-action-grid">
        <article>
          <header><b>证据覆盖</b></header>
          <div class="skill-cloud">
            <span v-for="skill in matchedSkills.slice(0, 18)" :key="skill" class="tag green">{{ skill }}</span>
            <span v-if="!matchedSkills.length" class="tag">待补充证据</span>
          </div>
        </article>
        <article>
          <header><b>能力缺口</b></header>
          <div class="skill-cloud">
            <span v-for="skill in missingSkills.slice(0, 18)" :key="skill" class="tag red">{{ skill }}</span>
            <span v-if="!missingSkills.length" class="tag">无缺口</span>
          </div>
        </article>
        <article class="match-action-panel">
          <header><b>面试核验建议</b></header>
          <div class="match-action-list">
            <div v-for="item in interviewActions" :key="item.b">
              <span>{{ item.span }}</span>
              <b>{{ item.b }}</b>
              <p>{{ item.p }}</p>
            </div>
          </div>
          <RouterLink to="/learning" class="button secondary">
            <AppIcon name="route" :size="15" />进入学习路径规划
          </RouterLink>
        </article>
      </div>
    </section>

  </div>
</template>
