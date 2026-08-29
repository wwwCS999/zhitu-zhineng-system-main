<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import { useEnglishThemeText } from '@/composables/useEnglishThemeText'

type IntakeMode = 'text' | 'file' | 'image'
const { tx, phrase } = useEnglishThemeText()

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
  const message = String(err?.message || tx('解析失败，请稍后重试', 'Parsing failed, please try again later'))
  if (activeIntakeMode.value === 'image' || /图片|image|OCR|VISION|视觉/i.test(message)) {
    return tx(`${message}。请确认已配置支持图片输入的视觉模型，或上传清晰原图。`, `${message}. Please confirm that an image-capable vision model is configured, or upload a clear original image.`)
  }
  return message
}

async function refreshSnapshotRoles() {
  refreshing.value = true
  try {
    await load()
    ElMessage.success(tx('已同步候选人画像与岗位目录', 'Talent profiles and role catalog synced'))
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
    ElMessage.success(tx(`解析完成：${result.extraction?.personName || '候选人'}，完整率 ${result.metrics?.parseRate || 0}%`, `Parsed: ${result.extraction?.personName || 'Candidate'}, completeness ${result.metrics?.parseRate || 0}%`))
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
    ElMessage.warning(tx('请先粘贴简历文本', 'Please paste resume text first'))
    return
  }
  parsing.value = true
  try {
    const result: any = await api.parseResumeText(resumeText.value)
    parseResult.value = result
    await load()
    resumeId.value = result.resumeId
    ElMessage.success(tx(`文本解析完成，完整率 ${result.metrics?.parseRate || 0}%`, `Text parsing completed, completeness ${result.metrics?.parseRate || 0}%`))
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    parsing.value = false
  }
}

async function match() {
  if (!resumeId.value || !roleId.value) {
    ElMessage.warning(tx('请选择候选人画像和目标岗位', 'Please select a talent profile and target role'))
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
    personName: item.person_name || tx('候选人', 'Candidate'),
    skills: storedList(item.skills),
    projects: storedList(item.projects),
    education: item.education || tx('未识别', 'Not detected'),
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

const modeCards = computed(() => [
  { id: 'text' as const, title: tx('普通模式', 'Standard Mode'), desc: tx('粘贴文本快速验证字段抽取', 'Paste text to quickly validate field extraction'), icon: 'file', speed: '400-700ms' },
  { id: 'file' as const, title: tx('极速模式', 'Fast Mode'), desc: tx('PDF / Word / TXT 批量沉淀', 'Batch parsing for PDF / Word / TXT'), icon: 'upload', speed: tx('文档解析', 'Document parsing') },
  { id: 'image' as const, title: tx('精准模式', 'Precision Mode'), desc: tx('多模态理解图片版面结构', 'Multimodal understanding of resume layouts'), icon: 'image', speed: tx('视觉模型', 'Vision model') }
])

const activeModeHint = computed(() => {
  if (activeIntakeMode.value === 'image') {
    return {
      title: tx('点击或拖拽，上传简历图片', 'Click or drag to upload a resume image'),
      desc: tx('支持 PNG / JPG / JPEG / WEBP / BMP，系统先读版面再做结构化校验。', 'Supports PNG / JPG / JPEG / WEBP / BMP. The system reads layout first, then validates structured fields.'),
      accept: 'image/png,image/jpeg,image/jpg,image/webp,image/bmp'
    }
  }
  return {
    title: tx('点击或拖拽，上传简历文件', 'Click or drag to upload a resume file'),
    desc: tx('支持 PDF / Word / TXT，单个文件不超过 20MB。', 'Supports PDF / Word / TXT. Single file size limit: 20MB.'),
    accept: '.pdf,.doc,.docx,.txt'
  }
})

const visionRuntime = computed(() => ({
  enabled: Boolean(modelStatus.value?.visionImageCapable),
  model: modelStatus.value?.visionModel || tx('未配置', 'Not configured'),
  textModel: modelStatus.value?.model || 'deepseek-chat'
}))

function compactEvidenceText(value: any) {
  return String(value || '').replace(/^[\s▌●■◆◇▶▷►★☆•·]+/, '').replace(/\s+/g, '')
}

function validProjectEvidence(item: any) {
  const name = compactEvidenceText(item?.name)
  const desc = compactEvidenceText(item?.description || item?.evidence)
  if (!name) return false
  if (/^(核心科研与项目经历|科研与项目经历|核心项目经历|项目经历|项目经验|实习经历与工程实践|工作经历与工程实践|工程实践|实习经历|工作经历)$/.test(name)) return false
  if (/(实习经历|工作经历|教育背景|教育经历|专业技能|技能清单)/.test(name)) return false
  return desc.length > name.length || /(系统|平台|应用|模型|算法|RAG|Agent|Graph|Platform|System|Dashboard|Service)/i.test(name)
}

function validEducationEvidence(item: any) {
  const degree = compactEvidenceText(item?.degree)
  const school = compactEvidenceText(item?.school)
  const major = compactEvidenceText(item?.major)
  const text = compactEvidenceText([item?.school, item?.major, item?.degree, item?.period, item?.evidence].filter(Boolean).join(' '))
  const degreeOk = /^(博士|硕士|本科|专科|大专|学士|研究生|Master|Bachelor|PhD|Doctor)$/i.test(degree)
    || /(博士|硕士|本科|专科|大专|学士|研究生|Master|Bachelor|PhD|Doctor)/i.test(text)
  const schoolOk = Boolean(school) && /(大学|学院|学校|University|College|Institute)/i.test(school)
  const polluted = /(实习生|工程师|开发者|算法负责人|项目负责人|核心开发|课题组|公司|企业|事业部|研究院).*(20\d{2}|19\d{2})/.test(text)
    && !schoolOk
  return (schoolOk || degreeOk) && !polluted && (school || major || degreeOk)
}

function validExperienceEvidence(item: any) {
  const company = compactEvidenceText(item?.company)
  const role = compactEvidenceText(item?.role)
  if (!company || !item?.period) return false
  if (/^(为|针对|通过|基于|负责|参与|协助|主导|优化|设计|实现|开发|完成|模型开发|数据处理|团队协作|工作内容|项目职责)/.test(company)) return false
  if (/(准确率|召回率|转化率|点击率|推理效率|情境|任务|行动|结果)/.test(company + role)) return false
  if (/(项目|系统|平台|模型|算法|框架|服务|产品)/.test(company) && !/(公司|集团|企业|研究院|研究所|实验室|中心|事业部|银行|证券|部门|部)$/.test(company)) return false
  if (role.length > 46) return false
  return true
}

function validSkillTag(skill: any) {
  const value = compactEvidenceText(skill)
  if (!value) return false
  if (/^(操作系统|离散数学|数据库原理与应用|数据库原理|计算机组成原理|编译原理|大学英语|高等数学|线性代数|概率论|数理统计|专业英语)$/.test(value)) return false
  if (/(经验|背景|方向|岗位|职责|内容|描述|项目|经历|证书|课程|专业|论文)$/.test(value)) return false
  if (/(沟通|协作|责任|抗压|认真|积极|细心|执行力|适应力|表达能力|团队)/.test(value)) return false
  return true
}

const projectEvidence = computed(() => (profileResult.value?.extraction?.details?.projectDetails || []).filter(validProjectEvidence))
const experienceEvidence = computed(() => (profileResult.value?.extraction?.details?.internships || []).filter(validExperienceEvidence))
const educationEvidence = computed(() => (profileResult.value?.extraction?.details?.educationBackground || []).filter(validEducationEvidence))
const skills = computed(() => (profileResult.value?.extraction?.skills || []).filter(validSkillTag))
const parseRate = computed(() => Number(profileResult.value?.metrics?.parseRate || 0))

const outputCards = computed(() => [
  { label: tx('技能标签', 'Skill Tags'), value: `${skills.value.length} ${tx('项', 'items')}`, desc: tx('用于岗位能力覆盖判断', 'Used for role capability coverage'), icon: 'spark' },
  { label: tx('项目证据', 'Project Evidence'), value: `${projectEvidence.value.length} ${tx('个', 'projects')}`, desc: tx('只统计显式项目区块', 'Only explicit project sections are counted'), icon: 'briefcase' },
  { label: tx('工作/实习', 'Work / Internship'), value: `${experienceEvidence.value.length} ${tx('段', 'records')}`, desc: tx('按公司、岗位、时间沉淀', 'Structured by company, role and period'), icon: 'pulse' },
  { label: tx('学历背景', 'Education Background'), value: profileResult.value?.extraction?.education || tx('待解析', 'Pending'), desc: tx('用于门槛条件校验', 'Used for threshold validation'), icon: 'book' }
])

const hasProjectBoundaryRisk = computed(() => {
  const issues = profileResult.value?.metrics?.qualityIssues || []
  return issues.some((item: string) => /项目|证据/.test(item))
})

const acceptanceChecks = computed(() => [
  { label: tx('字段完整率', 'Field Completeness'), value: `${parseRate.value}%`, ok: parseRate.value >= 90 },
  { label: tx('项目边界', 'Project Boundary'), value: `${projectEvidence.value.length} ${tx('个', 'projects')}`, ok: Boolean(profileResult.value) && !hasProjectBoundaryRisk.value },
  { label: tx('经历证据', 'Experience Evidence'), value: `${experienceEvidence.value.length} ${tx('段', 'records')}`, ok: experienceEvidence.value.length > 0 || Boolean(profileResult.value) },
  { label: tx('学历证据', 'Education Evidence'), value: `${educationEvidence.value.length} ${tx('条', 'records')}`, ok: educationEvidence.value.length > 0 }
])

const ledgerRows = computed(() => {
  const rows: any[] = []
  projectEvidence.value.forEach((item: any, index: number) => rows.push({
    id: `p-${index}`,
    type: tx('项目', 'Project'),
    title: item.name || `${tx('项目', 'Project')} ${index + 1}`,
    period: item.period || item.source || tx('项目区块', 'Project section'),
    evidence: item.evidence || item.description || tx('已结构化', 'Structured'),
    tone: 'blue'
  }))
  experienceEvidence.value.forEach((item: any, index: number) => rows.push({
    id: `e-${index}`,
    type: tx('经历', 'Experience'),
    title: [item.company, item.role].filter(Boolean).join(' · ') || `${tx('经历', 'Experience')} ${index + 1}`,
    period: item.period || tx('时间待确认', 'Period pending'),
    evidence: item.evidence || item.description || tx('已结构化', 'Structured'),
    tone: 'green'
  }))
  educationEvidence.value.forEach((item: any, index: number) => rows.push({
    id: `edu-${index}`,
    type: tx('学历', 'Education'),
    title: [item.school, item.major].filter(Boolean).join(' · ') || `${tx('学历', 'Education')} ${index + 1}`,
    period: item.period || item.degree || tx('学历区块', 'Education section'),
    evidence: item.evidence || item.degree || tx('已结构化', 'Structured'),
    tone: 'mint'
  }))
  return rows.slice(0, 8)
})

const readinessPercent = computed(() => {
  const checks = [Boolean(resumeId.value), Boolean(roleId.value), parseRate.value >= 75]
  return Math.round((checks.filter(Boolean).length / checks.length) * 100)
})

const targetSetupMetrics = computed(() => [
  { label: tx('画像完整率', 'Profile Completeness'), value: profileResult.value ? `${parseRate.value}%` : tx('待解析', 'Pending'), desc: tx('候选人字段质量', 'Candidate field quality') },
  { label: tx('项目证据', 'Project Evidence'), value: `${projectEvidence.value.length} ${tx('个', 'projects')}`, desc: tx('匹配解释来源', 'Source for matching explanation') },
  { label: tx('目标岗位', 'Target Role'), value: phrase(selectedRole.value?.level_name || tx('待选择', 'Pending')), desc: phrase(selectedRole.value?.tech_stack || tx('技术方向待确认', 'Technical domain pending')) }
])

const matchPolicies = computed(() => [
  { id: 'balanced' as const, label: tx('标准筛选', 'Balanced Screening'), desc: tx('兼顾技能、项目、经历和学历，适合常规初筛。', 'Balances skills, projects, experience and education for standard screening.') },
  { id: 'strict' as const, label: tx('严格技能', 'Strict Skill Fit'), desc: tx('优先看必备技能和技术栈，适合交付压力高的岗位。', 'Prioritizes required skills and tech stack for delivery-critical roles.') },
  { id: 'growth' as const, label: tx('培养潜力', 'Growth Potential'), desc: tx('关注学习路径和转岗价值，适合储备人才。', 'Focuses on learning path and transfer potential for talent reserves.') }
])
const selectedPolicy = computed(() => matchPolicies.value.find(item => item.id === matchPolicy.value) || matchPolicies.value[0])

function decisionLevel(score: unknown) {
  const value = Number(score || 0)
  if (value >= 85) return tx('优先推进', 'Prioritize')
  if (value >= 70) return tx('可进入面试', 'Interview Ready')
  if (value >= 55) return tx('进入培养池', 'Development Pool')
  return tx('暂不推荐', 'Not Recommended')
}

const dimensionRows = computed(() => Object.entries(report.value?.dimensions || {}).map(([label, raw]) => ({
  label: phrase(label),
  value: Number(raw || 0),
  status: Number(raw || 0) >= 80 ? tx('优势', 'Strength') : Number(raw || 0) >= 65 ? tx('可用', 'Usable') : tx('待补齐', 'Gap')
})))

const matchedSkills = computed(() => report.value?.matchedSkills || [])
const missingSkills = computed(() => report.value?.missingSkills || [])
const candidateName = computed(() => phrase(selectedResume.value?.person_name || tx('待选择候选人', 'Select Candidate')))
const targetRoleName = computed(() => phrase(selectedRole.value?.role_name || tx('待选择目标岗位', 'Select Target Role')))
const targetLevelName = computed(() => phrase(selectedRole.value?.level_name || tx('岗位等级待确认', 'Role level pending')))
const targetTechStack = computed(() => phrase(selectedRole.value?.tech_stack || tx('技术方向待确认', 'Technical domain pending')))
const selectedModeTitle = computed(() => modeCards.value.find(item => item.id === activeIntakeMode.value)?.title || tx('解析通道', 'Parsing Channel'))

const kpiMetrics = computed(() => [
  { label: tx('画像完整率', 'Profile Completeness'), value: `${parseRate.value}%`, desc: profileResult.value ? tx('候选人字段抽取质量', 'Candidate field extraction quality') : tx('解析简历后自动更新', 'Updates after resume parsing') },
  { label: tx('技能标签', 'Skill Tags'), value: `${skills.value.length} ${tx('项', 'items')}`, desc: tx('用于岗位能力覆盖判断', 'Used for capability coverage judgment') },
  { label: tx('项目证据', 'Project Evidence'), value: `${projectEvidence.value.length} ${tx('个', 'projects')}`, desc: tx('匹配解释的可追溯来源', 'Traceable source for matching explanation') },
  { label: tx('匹配准备度', 'Matching Readiness'), value: `${readinessPercent.value}%`, desc: tx('画像 + 岗位 + 完整率就绪', 'Profile + role + completeness ready') }
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
      span: `${tx('维度核验', 'Dimension Check')} 0${index + 1}`,
      b: tx(`深挖「${row.label}」短板`, `Deep-dive the “${row.label}” gap`),
      p: tx('要求候选人结合项目数据说明当前水平，评估差距是否可在短期补齐。', 'Ask the candidate to explain current capability with project data and assess whether the gap can be closed quickly.')
    })
  })
  missingSkills.value.slice(0, 2).forEach((skill: string, index: number) => {
    items.push({
      span: `${tx('技能核验', 'Skill Check')} 0${index + 1}`,
      b: tx(`核验「${skill}」落地能力`, `Verify practical ability in “${phrase(skill)}”`),
      p: tx('询问最近一次使用该技能的项目场景、结果与复盘，避免简历话术。', 'Ask about the most recent project scenario, outcome and review to avoid resume-only claims.')
    })
  })
  if (!items.length) {
    items.push({ span: tx('建议', 'Recommendation'), b: tx('综合匹配通过', 'Overall Match Passed'), p: tx('未发现明显短板，可进入面试环节做软素质核验。', 'No obvious gaps were found; proceed to interview for soft-skill verification.') })
  }
  return items.slice(0, 3)
})

function riskHintForSkill(index: number) {
  const hints = [
    tx('面试重点核验该项能力是否真实落地。', 'Verify whether this capability has been applied in real work.'),
    tx('要求候选人用项目经历说明该技能的深度。', 'Ask the candidate to prove skill depth through project experience.'),
    tx('关注是否存在课程学习但缺少真实交付的场景。', 'Check whether the skill is only coursework without real delivery.'),
    tx('核验该项技能与目标岗位的实际匹配度。', 'Verify how well this skill fits the target role.'),
    tx('评估是否可通过短期任务补齐到可用水平。', 'Assess whether a short task can close the gap to usable level.'),
    tx('结合岗位证据判断该技能是否为核心准入项。', 'Use role evidence to decide whether this is a core entry requirement.')
  ]
  return hints[index % hints.length]
}

onMounted(load)
</script>

<template>
  <div class="match-product-page">
    <section class="match-command-center" v-reveal>
      <div class="match-command-copy">
        <span class="match-kicker">{{ tx('画像匹配智能体 · 简历解析与人岗匹配', 'Talent Matching Agent · Resume Parsing & Person-Job Matching') }}</span>
        <h1>{{ tx('人岗匹配诊断工作台', 'Person-Job Matching Diagnosis Workbench') }}</h1>
        <p>{{ tx('解析候选人简历，沉淀项目、经历与学历证据，结合岗位能力图谱生成可解释的匹配诊断报告。', 'Parse candidate resumes, structure project, experience and education evidence, and generate explainable matching reports with the capability graph.') }}</p>
        <div class="match-command-actions">
          <button class="button primary" type="button" :disabled="loading" @click="match">
            <AppIcon name="match" :size="16" />{{ loading ? tx('诊断中', 'Diagnosing') : tx('生成匹配报告', 'Generate Match Report') }}
          </button>
          <button class="button secondary" type="button" :disabled="refreshing" @click="refreshSnapshotRoles">
            <AppIcon name="refresh" :size="16" />{{ refreshing ? tx('刷新中', 'Refreshing') : tx('刷新画像库', 'Refresh Profiles') }}
          </button>
        </div>
      </div>

      <aside class="match-readiness-card">
        <div class="match-readiness-head">
          <span>{{ tx('当前匹配对象', 'Current Match Target') }}</span>
          <b>{{ candidateName }}</b>
        </div>
        <div class="match-route-line">
          <span>{{ candidateName }}</span>
          <AppIcon name="arrow" :size="15" />
          <span>{{ targetRoleName }}</span>
        </div>
        <div class="match-readiness-meter">
          <div>
            <span>{{ tx('匹配准备度', 'Matching Readiness') }}</span>
            <strong>{{ readinessPercent }}%</strong>
          </div>
          <ProgressBar :value="readinessPercent" :tone="readinessPercent >= 75 ? 'mint' : 'gold'" />
        </div>
        <div class="match-ready-list">
          <span :class="{ done: Boolean(resumeId) }"><AppIcon name="check" :size="13" />{{ tx('候选人画像', 'Talent Profile') }}</span>
          <span :class="{ done: Boolean(roleId) }"><AppIcon name="check" :size="13" />{{ tx('目标岗位', 'Target Role') }}</span>
          <span :class="{ done: parseRate >= 75 }"><AppIcon name="check" :size="13" />{{ tx('完整率 ≥75%', 'Completeness ≥75%') }}</span>
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
            <span class="match-kicker">{{ tx('简历解析', 'Resume Parsing') }}</span>
            <h2>{{ tx('候选人画像解析', 'Talent Profile Extraction') }}</h2>
            <p>{{ tx('选择解析通道，秒级抽取结构化字段，并把项目、经历、学历证据沉淀到人岗匹配链路。', 'Choose a parsing channel to extract structured fields in seconds and feed project, experience and education evidence into matching.') }}</p>
          </div>
          <span class="status-badge" :class="visionRuntime.enabled ? 'good' : 'warn'">
            {{ visionRuntime.enabled ? tx('视觉模型已启用', 'Vision model enabled') : tx('视觉模型未配置', 'Vision model not configured') }}
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
              <b>{{ tx('证据门禁', 'Evidence Gate') }}</b>
              <span>{{ tx('项目、经历、学历只统计显式区块，每条保留可追溯来源。', 'Projects, experience and education are counted only from explicit sections, with traceable sources retained.') }}</span>
            </div>
          </aside>

          <div class="profile-compose-panel">
            <header class="profile-compose-head">
              <div>
                <span class="eyebrow">{{ selectedModeTitle }}</span>
                <h3>{{ activeModeHint.title }}</h3>
              </div>
              <span class="status-badge" :class="visionRuntime.enabled ? 'good' : 'warn'">
                {{ visionRuntime.enabled ? tx('视觉已启用', 'Vision Enabled') : tx('OCR 兜底', 'OCR Fallback') }}
              </span>
            </header>

            <section v-if="activeIntakeMode === 'text'" class="profile-text-parser">
              <textarea v-model="resumeText" class="textarea" rows="8" />
              <div class="profile-parser-actions">
                <span>{{ tx('粘贴简历文本，快速验证字段抽取质量。', 'Paste resume text to quickly validate extraction quality.') }}</span>
                <button class="button primary" type="button" :disabled="parsing" @click="parseText">
                  <AppIcon name="scan" :size="15" />{{ parsing ? tx('解析中', 'Parsing') : tx('解析文本', 'Parse Text') }}
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
              <b>{{ parsing ? tx('正在解析简历', 'Parsing Resume') : activeModeHint.title }}</b>
              <small>{{ activeModeHint.desc }}</small>
            </label>

            <div v-if="activeIntakeMode === 'image'" class="profile-image-pipeline">
              <div><span>STEP 01</span><b>{{ tx('版面读取', 'Layout Reading') }}</b><small>{{ tx('OCR 还原文本区块', 'OCR restores text blocks') }}</small></div>
              <div><span>STEP 02</span><b>{{ tx('视觉理解', 'Vision Understanding') }}</b><small>{{ tx('Qwen-VL 结构校准', 'Qwen-VL structure calibration') }}</small></div>
              <div><span>STEP 03</span><b>{{ tx('证据校验', 'Evidence Validation') }}</b><small>{{ tx('DeepSeek 门禁复核', 'DeepSeek gate review') }}</small></div>
            </div>

            <div class="profile-vision-runtime">
              <div class="profile-vision-runtime-main">
                <span>{{ tx('文本模型', 'Text Model') }}</span>
                <b>{{ visionRuntime.textModel }}</b>
                <p>{{ visionRuntime.enabled ? tx('视觉模型已接入，图片简历走多模态理解。', 'Vision model is connected; image resumes use multimodal understanding.') : tx('未配置视觉模型，图片简历自动走 OCR 兜底。', 'Vision model is not configured; image resumes use OCR fallback.') }}</p>
              </div>
              <div class="profile-vision-runtime-model">
                <span>{{ tx('视觉模型', 'Vision Model') }}</span>
                <b>{{ visionRuntime.model }}</b>
              </div>
            </div>
          </div>

          <aside class="profile-output-panel">
            <header>
              <b>{{ tx('画像输出概览', 'Profile Output Overview') }}</b>
              <span class="status-badge" :class="parseRate >= 75 ? 'good' : 'warn'">{{ parseRate }}% {{ tx('完整率', 'Completeness') }}</span>
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
              <span>{{ tx('完整率', 'Completeness') }}</span>
            </div>
            <div class="parse-profile-person">
              <span class="status-badge good">{{ phrase(profileResult.metrics?.extractionMode || tx('企业解析链路', 'Enterprise Parsing Pipeline')) }}</span>
              <h3>{{ phrase(profileResult.extraction?.personName || tx('候选人', 'Candidate')) }}</h3>
              <div class="parse-profile-facts">
                <span>{{ phrase(profileResult.extraction?.education || tx('学历待确认', 'Education pending')) }}</span>
                <span>{{ profileResult.extraction?.experienceYears ?? 0 }} {{ tx('年经验', 'years experience') }}</span>
                <span>{{ skills.length }} {{ tx('项技能', 'skills') }}</span>
                <span>{{ projectEvidence.length }} {{ tx('个项目', 'projects') }}</span>
              </div>
            </div>
          </div>

          <div class="parse-result-evidence">
            <header>
              <div>
                <span class="eyebrow">{{ tx('解析结果', 'Parsing Result') }}</span>
                <b>{{ tx('关键经历证据', 'Key Experience Evidence') }}</b>
              </div>
              <span class="status-badge" :class="parseRate >= 90 ? 'good' : 'warn'">
                {{ parseRate >= 90 ? tx('达到验收线', 'Accepted') : tx('建议复核', 'Review Suggested') }}
              </span>
            </header>
            <div class="parse-evidence-list">
              <article v-for="item in ledgerRows.slice(0, 5)" :key="item.id" :class="`tone-${item.tone}`">
                <span>{{ item.type }}</span>
                <div>
                  <b>{{ phrase(item.title) }}</b>
                  <small>{{ item.period }}</small>
                  <p>{{ phrase(item.evidence) }}</p>
                </div>
              </article>
              <article v-if="!ledgerRows.length" class="empty">
                <span>{{ tx('待补充', 'Pending') }}</span>
                <div>
                  <b>{{ tx('暂无可展开证据', 'No expandable evidence yet') }}</b>
                  <small>{{ tx('上传清晰简历后自动生成', 'Generated after uploading a clear resume') }}</small>
                  <p>{{ tx('系统会优先展示项目、工作/实习和学历三类结构化证据。', 'The system prioritizes project, work/internship and education evidence.') }}</p>
                </div>
              </article>
            </div>
          </div>

          <aside class="parse-result-side">
            <div class="parse-result-mini-grid">
              <div>
                <span>{{ tx('项目', 'Projects') }}</span>
                <b>{{ projectEvidence.length }}</b>
              </div>
              <div>
                <span>{{ tx('经历', 'Experience') }}</span>
                <b>{{ experienceEvidence.length }}</b>
              </div>
              <div>
                <span>{{ tx('学历', 'Education') }}</span>
                <b>{{ educationEvidence.length }}</b>
              </div>
            </div>
            <div class="parse-result-skills">
              <span class="eyebrow">{{ tx('核心技能', 'Core Skills') }}</span>
              <div class="skill-cloud compact">
                <span v-for="skill in skills.slice(0, 12)" :key="skill" class="tag blue">{{ phrase(skill) }}</span>
                <span v-if="!skills.length" class="tag">{{ tx('待解析', 'Pending') }}</span>
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
              <span class="eyebrow">{{ tx('待解析画像', 'Profile Pending') }}</span>
              <h3>{{ tx('上传简历，生成候选人画像报告', 'Upload a resume to generate a talent profile report') }}</h3>
              <p>{{ tx('系统抽取技能、项目、经历和学历证据，并输出完整率与验收建议。', 'The system extracts skills, projects, experience and education evidence, then outputs completeness and acceptance suggestions.') }}</p>
            </div>
            <div class="profile-standby-state">
              <b>{{ parseRate ? `${parseRate}%` : '--' }}</b>
              <span>{{ tx('完整率', 'Completeness') }}</span>
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
              <header><b>{{ tx('企业验收线', 'Enterprise Acceptance Line') }}</b></header>
              <div class="profile-standby-checks">
                <span v-for="item in acceptanceChecks" :key="item.label" :class="{ done: item.ok }">
                  <AppIcon :name="item.ok ? 'check' : 'focus'" :size="12" />{{ item.label }} · {{ item.value }}
                </span>
              </div>
            </div>
            <div class="profile-standby-card">
              <header><b>{{ tx('解析链路', 'Parsing Pipeline') }}</b></header>
              <div class="profile-standby-evidence">
                <div><span>{{ tx('普通模式', 'Standard Mode') }}</span><b>{{ tx('400-700ms 文本抽取', '400-700ms text extraction') }}</b></div>
                <div><span>{{ tx('极速模式', 'Fast Mode') }}</span><b>{{ tx('PDF / Word / TXT 批量', 'PDF / Word / TXT batch') }}</b></div>
                <div><span>{{ tx('精准模式', 'Precision Mode') }}</span><b>{{ tx('多模态版面理解', 'Multimodal layout understanding') }}</b></div>
                <div><span>{{ tx('证据门禁', 'Evidence Gate') }}</span><b>{{ tx('每类字段保留来源', 'Source retained for each field') }}</b></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <aside class="match-target-panel surface" v-reveal="120">
        <header class="match-panel-head compact">
          <div>
            <span class="match-kicker">{{ tx('匹配目标', 'Matching Target') }}</span>
            <h2>{{ tx('锁定候选人与岗位', 'Lock Candidate and Role') }}</h2>
            <p>{{ tx('选择候选人画像与标准岗位画像，生成可解释的招聘筛选结论。', 'Select a talent profile and standard role profile to generate explainable screening conclusions.') }}</p>
          </div>
        </header>

        <div class="match-lock-stack">
          <div class="match-lock-card">
            <div class="match-lock-head">
              <span>{{ tx('匹配准备度', 'Matching Readiness') }}</span>
              <b>{{ readinessPercent }}%</b>
            </div>
            <ProgressBar :value="readinessPercent" :tone="readinessPercent >= 75 ? 'mint' : 'gold'" />
            <div class="match-lock-route">
              <div>
                <small>{{ tx('候选人', 'Candidate') }}</small>
                <b>{{ candidateName }}</b>
                <span>{{ phrase(selectedResume?.education || tx('学历待确认', 'Education pending')) }}</span>
              </div>
              <AppIcon name="arrow" :size="16" />
              <div>
                <small>{{ tx('目标岗位', 'Target Role') }}</small>
                <b>{{ targetRoleName }}</b>
                <span>{{ targetLevelName }} · {{ targetTechStack }}</span>
              </div>
            </div>
            <div class="match-ready-pills">
              <span :class="{ done: Boolean(resumeId) }"><AppIcon name="check" :size="11" />{{ tx('候选人画像', 'Talent Profile') }}</span>
              <span :class="{ done: Boolean(roleId) }"><AppIcon name="check" :size="11" />{{ tx('目标岗位', 'Target Role') }}</span>
              <span :class="{ done: parseRate >= 75 }"><AppIcon name="check" :size="11" />{{ tx('完整率 ≥75%', 'Completeness ≥75%') }}</span>
            </div>
          </div>

          <div class="target-selector-stack">
            <label class="field">
              <span>{{ tx('候选人画像', 'Talent Profile') }}</span>
              <select v-model="resumeId" class="select">
                <option v-for="item in resumes" :key="item.id" :value="item.id">{{ phrase(item.person_name) }} · {{ phrase(item.education) }} · {{ tx('解析', 'Parsed') }} {{ parsePercent(item) }}%</option>
              </select>
            </label>
            <label class="field">
              <span>{{ tx('目标岗位', 'Target Role') }}</span>
              <select v-model="roleId" class="select">
                <option v-for="item in roles" :key="item.id" :value="item.id">{{ phrase(item.role_name) }} · {{ phrase(item.level_name) }}</option>
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
                <span class="eyebrow">{{ tx('匹配策略', 'Matching Policy') }}</span>
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
            <AppIcon name="match" :size="16" />{{ loading ? tx('诊断中', 'Diagnosing') : tx('生成匹配报告', 'Generate Match Report') }}
          </button>
          <button class="button secondary full-width" type="button" :disabled="refreshing" @click="refreshSnapshotRoles">
            <AppIcon name="refresh" :size="16" />{{ refreshing ? tx('刷新中', 'Refreshing') : tx('刷新画像库', 'Refresh Profiles') }}
          </button>

          <div v-if="!report" class="match-target-next-step">
            <span class="eyebrow">{{ tx('诊断待生成', 'Diagnosis Pending') }}</span>
            <b>{{ tx('完成画像与岗位锁定后生成报告', 'Generate the report after locking profile and role') }}</b>
            <p>{{ tx('报告将汇总匹配度、技能覆盖、项目证据、能力缺口和面试核验建议。', 'The report summarizes matching score, skill coverage, project evidence, capability gaps and interview checks.') }}</p>
            <div>
              <span :class="{ done: Boolean(resumeId) }"><AppIcon name="check" :size="11" />{{ tx('画像', 'Profile') }}</span>
              <span :class="{ done: Boolean(roleId) }"><AppIcon name="check" :size="11" />{{ tx('岗位', 'Role') }}</span>
              <span :class="{ done: parseRate >= 75 }"><AppIcon name="check" :size="11" />{{ tx('完整率', 'Completeness') }}</span>
            </div>
          </div>
        </div>
      </aside>
    </section>

    <section v-if="report" class="match-result-shell surface" v-reveal>
      <header class="match-report-hero">
        <div class="match-report-copy">
          <span class="match-kicker">{{ tx('匹配诊断报告', 'Matching Diagnosis Report') }}</span>
          <h2>{{ phrase(report.person_name) }} → {{ phrase(report.role_name) }}</h2>
          <p>{{ phrase(report.explanation) }}</p>
          <div class="match-report-route">
            <span>{{ candidateName }}</span>
            <span>{{ targetRoleName }}</span>
            <span>{{ targetLevelName }}</span>
            <span>{{ selectedPolicy.label }}</span>
          </div>
        </div>
        <aside class="match-decision-card" :class="decisionTone">
          <div>
            <span>{{ tx('招聘建议', 'Hiring Recommendation') }}</span>
            <em>{{ decisionLevel(report.overall_score) }}</em>
          </div>
          <strong>{{ report.overall_score }}%</strong>
          <b>{{ decisionLevel(report.overall_score) }}</b>
          <p>{{ tx('结合技能覆盖、项目证据和六维匹配给出用人方向建议。', 'Recommendation based on skill coverage, project evidence and six-dimensional matching.') }}</p>
        </aside>
      </header>

      <div class="match-result-cards">
        <article class="score">
          <span>{{ tx('综合匹配度', 'Overall Match') }}</span>
          <b>{{ report.overall_score }}%</b>
          <small>{{ decisionLevel(report.overall_score) }}</small>
        </article>
        <article>
          <span>{{ tx('已覆盖技能', 'Covered Skills') }}</span>
          <b>{{ matchedSkills.length }} {{ tx('项', 'items') }}</b>
          <small>{{ tx('来自简历证据', 'From resume evidence') }}</small>
        </article>
        <article :class="{ warn: missingSkills.length > 0 }">
          <span>{{ tx('待补齐技能', 'Missing Skills') }}</span>
          <b>{{ missingSkills.length }} {{ tx('项', 'items') }}</b>
          <small>{{ tx('用于面试核验', 'For interview verification') }}</small>
        </article>
        <article>
          <span>{{ tx('匹配策略', 'Matching Policy') }}</span>
          <b>{{ selectedPolicy.label }}</b>
          <small>{{ selectedPolicy.desc }}</small>
        </article>
      </div>

      <div class="match-report-body-grid">
        <div class="match-weight-panel">
          <header>
            <h3>{{ tx('六维匹配', 'Six-dimensional Matching') }}</h3>
            <small>{{ tx('技能 / 实习 / 项目 / 技术栈 / 岗位等级 / 学历 加权归一', 'Skills / Internship / Projects / Tech Stack / Role Level / Education weighted and normalized') }}</small>
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
            <h3>{{ tx('能力缺口', 'Capability Gaps') }}</h3>
            <span>{{ missingSkills.length }} {{ tx('项待补齐', 'items to close') }}</span>
          </header>
          <div class="match-risk-list">
            <div v-for="(skill, index) in missingSkills.slice(0, 6)" :key="skill" class="danger">
              <b>{{ phrase(skill) }}</b>
              <span>Gap</span>
              <p>{{ riskHintForSkill(index) }}</p>
            </div>
            <div v-if="!missingSkills.length" class="good">
              <b>{{ tx('无硬性缺口', 'No hard-skill gaps') }}</b>
              <span>OK</span>
              <p>{{ tx('已覆盖岗位必备技能，可进入面试核验环节。', 'Required role skills are covered; proceed to interview verification.') }}</p>
            </div>
          </div>
        </aside>
      </div>

      <div class="match-evidence-action-grid">
        <article>
          <header><b>{{ tx('证据覆盖', 'Evidence Coverage') }}</b></header>
          <div class="skill-cloud">
            <span v-for="skill in matchedSkills.slice(0, 18)" :key="skill" class="tag green">{{ phrase(skill) }}</span>
            <span v-if="!matchedSkills.length" class="tag">{{ tx('待补充证据', 'Evidence pending') }}</span>
          </div>
        </article>
        <article>
          <header><b>{{ tx('能力缺口', 'Capability Gaps') }}</b></header>
          <div class="skill-cloud">
            <span v-for="skill in missingSkills.slice(0, 18)" :key="skill" class="tag red">{{ phrase(skill) }}</span>
            <span v-if="!missingSkills.length" class="tag">{{ tx('无缺口', 'No gaps') }}</span>
          </div>
        </article>
        <article class="match-action-panel">
          <header><b>{{ tx('面试核验建议', 'Interview Verification Suggestions') }}</b></header>
          <div class="match-action-list">
            <div v-for="item in interviewActions" :key="item.b">
              <span>{{ item.span }}</span>
              <b>{{ item.b }}</b>
              <p>{{ item.p }}</p>
            </div>
          </div>
          <RouterLink to="/learning" class="button secondary">
            <AppIcon name="route" :size="15" />{{ tx('进入学习路径规划', 'Enter Learning Path Planning') }}
          </RouterLink>
        </article>
      </div>
    </section>

  </div>
</template>
