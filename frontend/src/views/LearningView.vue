<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import { useEnglishThemeText } from '@/composables/useEnglishThemeText'

const { tx, phrase } = useEnglishThemeText()

const matches = ref<any[]>([])
const resumes = ref<any[]>([])
const paths = ref<any[]>([])
const matchId = ref<number>()
const selectedPlanModes = ref<string[]>(['SKILL_GAP', 'ONBOARDING'])
const detail = ref<any>(null)
const matchDetail = ref<any>(null)
const loading = ref(false)
const refreshing = ref(false)
const optimizing = ref(false)
const selectedStageIndex = ref(0)
const completedStageCount = ref(0)
const PHASE_PLAN_WEEKS = 12
const PHASE_PLAN_HOURS = 8

const planModes = [
  { value: 'SKILL_GAP', label: '缺口补齐', desc: '优先处理岗位硬性技能缺口，适合招聘筛选后的短期培养。' },
  { value: 'ONBOARDING', label: '试用转正', desc: '强调基础规范、交付节奏和企业项目协作。' },
  { value: 'PROMOTION', label: '晋升储备', desc: '提升复杂项目、业务指标和架构表达能力。' },
  { value: 'PORTFOLIO', label: '作品集强化', desc: '把学习结果沉淀成可面试、可复盘、可展示的作品证据。' }
]

const strategyRouteMeta: Record<string, { route: string; acceptance: string; output: string }> = {
  SKILL_GAP: {
    route: 'P0 缺口优先',
    acceptance: '硬性技能通过专项验收',
    output: '短板清单'
  },
  ONBOARDING: {
    route: '试用期上岗',
    acceptance: '企业规范与交付节奏达标',
    output: '上岗任务包'
  },
  PROMOTION: {
    route: '晋升储备',
    acceptance: '复杂任务与业务指标可解释',
    output: '晋升证据'
  },
  PORTFOLIO: {
    route: '作品集沉淀',
    acceptance: '项目证据可演示、可复盘',
    output: '作品集'
  }
}

function parseList(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String).filter(Boolean)
  if (value == null || value === '') return []
  const text = String(value)
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean)
  } catch {
    /* 非 JSON，走分隔符 */
  }
  return text.split(/[；;,\n]/).map(item => item.trim()).filter(Boolean)
}

function toNumber(value: unknown, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function formatNumber(value: unknown, digits = 0) {
  return toNumber(value).toFixed(digits).replace(/\.0$/, '')
}

function shortText(value: unknown, fallback = '待生成') {
  const text = String(value ?? '').trim()
  return text || fallback
}

function compactText(value: unknown, fallback = '待确认', max = 34) {
  const text = shortText(value, fallback).replace(/\s+/g, ' ')
  return text.length > max ? `${text.slice(0, max)}…` : text
}

function stepItems(step: any, key: string, fallbackKey?: string, limit = 4) {
  return parseList(step?.[key] ?? (fallbackKey ? step?.[fallbackKey] : null)).slice(0, limit)
}

function stageScore(step: any) {
  const listCoverage = ['topics', 'weeklyTasks', 'deliverables', 'assessment', 'successCriteria']
    .reduce((sum, key) => sum + Math.min(stepItems(step, key, undefined, 4).length, 4) * 5, 0)
  const textCoverage = ['goal', 'rationale', 'weekRange']
    .filter(key => shortText(step?.[key], '')).length * 8
  return Math.min(100, Math.max(45, Math.round(listCoverage + textCoverage)))
}

function stageTone(score: number): 'mint' | 'gold' | 'ink' {
  if (score >= 85) return 'mint'
  if (score >= 65) return 'gold'
  return 'ink'
}

function isStageCompleted(index: number) {
  return index < completedStageCount.value
}

function isStageUnlocked(index: number) {
  return index <= completedStageCount.value
}

function stageStatusLabel(index: number) {
  if (isStageCompleted(index)) return '已完成'
  if (isStageUnlocked(index)) return index === 0 ? '可开始' : '已解锁'
  return '待解锁'
}

function selectStage(index: number) {
  if (!isStageUnlocked(index)) {
    ElMessage.warning('请先完成上一层金字塔任务')
    return
  }
  selectedStageIndex.value = index
}

function completeSelectedStage() {
  if (!selectedStage.value) return
  const nextCompleted = Math.max(completedStageCount.value, selectedStageIndex.value + 1)
  completedStageCount.value = Math.min(nextCompleted, pathSteps.value.length)
  if (selectedStageIndex.value < pathSteps.value.length - 1) {
    selectedStageIndex.value += 1
    ElMessage.success('已解锁下一层能力金字塔')
    return
  }
  ElMessage.success('已完成全部培养层级')
}

function togglePlanMode(value: string) {
  if (selectedPlanModes.value.includes(value)) {
    if (selectedPlanModes.value.length === 1) {
      ElMessage.warning('至少保留一个培养场景')
      return
    }
    selectedPlanModes.value = selectedPlanModes.value.filter(item => item !== value)
    return
  }
  selectedPlanModes.value = [...selectedPlanModes.value, value]
}

const selectedProfile = computed(() => {
  if (!matchDetail.value) return null
  return resumes.value.find(r => r.person_name === matchDetail.value.person_name) || null
})

const selectedMatch = computed(() => {
  return matches.value.find(item => Number(item.id) === Number(matchId.value)) || null
})

const candidateName = computed(() => {
  return matchDetail.value?.person_name || selectedMatch.value?.person_name || '待选择候选人'
})

const targetRoleName = computed(() => {
  return matchDetail.value?.role_name || selectedMatch.value?.role_name || '待选择目标岗位'
})

const targetLevelName = computed(() => {
  return matchDetail.value?.level_name || '岗位等级待确认'
})

const targetTechStack = computed(() => {
  return matchDetail.value?.tech_stack || '技术方向待确认'
})

const missingSkills = computed(() => parseList(matchDetail.value?.missingSkills ?? matchDetail.value?.missing_skills))
const matchedSkills = computed(() => parseList(matchDetail.value?.matchedSkills ?? matchDetail.value?.matched_skills))
const resumeSkills = computed(() => parseList(selectedProfile.value?.skills).slice(0, 18))
const resumeProjects = computed(() => parseList(selectedProfile.value?.projects).slice(0, 4))
const reusableSkills = computed(() => Array.from(new Set([...matchedSkills.value, ...resumeSkills.value])).slice(0, 18))
const pathSteps = computed(() => Array.isArray(detail.value?.steps) ? detail.value.steps : [])
const matchScore = computed(() => toNumber(matchDetail.value?.overall_score ?? selectedMatch.value?.overall_score))
const phaseStageCount = computed(() => pathSteps.value.length || 5)
const planTaskCount = computed(() => pathSteps.value.reduce((sum: number, step: any) => sum + stepItems(step, 'weeklyTasks', undefined, 12).length, 0))
const planDeliverableCount = computed(() => pathSteps.value.reduce((sum: number, step: any) => sum + stepItems(step, 'deliverables', 'deliverable', 12).length, 0))
const planAssessmentCount = computed(() => pathSteps.value.reduce((sum: number, step: any) => sum + stepItems(step, 'assessment', undefined, 12).length, 0))
const planDigitalScore = computed(() => {
  if (!pathSteps.value.length) return 0
  const total = pathSteps.value.reduce((sum: number, step: any) => sum + stageScore(step), 0)
  return Math.round(total / pathSteps.value.length)
})
const selectedStage = computed(() => pathSteps.value[selectedStageIndex.value] || pathSteps.value[0] || null)
const selectedStageMetrics = computed(() => {
  const step = selectedStage.value
  if (!step) return []
  return [
    { label: '当前门禁', value: selectedStageIndex.value + 1, unit: '层' },
    { label: '训练任务', value: stepItems(step, 'weeklyTasks', undefined, 12).length, unit: '项' },
    { label: '交付物', value: stepItems(step, 'deliverables', 'deliverable', 12).length, unit: '件' },
    { label: '验收口径', value: stepItems(step, 'assessment', undefined, 12).length, unit: '条' }
  ]
})
const plannerLabel = computed(() => {
  const model = String(detail.value?.modelName || '').trim()
  const mode = String(detail.value?.plannerMode || '').trim()
  if (model && model !== 'deterministic-fallback') return `AI 动态规划 · ${model}`
  if (mode.includes('AI')) return 'AI 动态规划'
  return '规则兜底规划'
})
const plannerIsAi = computed(() => plannerLabel.value.includes('AI'))
const displayPlanTitle = computed(() => shortText(
  detail.value?.title,
  `面向“${targetRoleName.value}”的阶段性岗位能力培养方案`
))
const displayPlanObjective = computed(() => {
  if (detail.value?.objective) return shortText(detail.value.objective)
  const role = targetRoleName.value
  const candidate = candidateName.value
  return `针对“${candidate}”与“${role}”的匹配结果，按岗位胜任阶段拆解为能力校准、专项训练、场景交付和上岗验收，所有阶段均以企业可复核证据作为解锁门禁。`
})
function stageDisplayLabel(step: any, index: number) {
  return shortText(step?.pyramidTier, `第 ${index + 1} 阶段`)
}
const climbProgressPercent = computed(() => {
  if (!pathSteps.value.length) return 0
  return Math.round(((selectedStageIndex.value + 1) / pathSteps.value.length) * 100)
})
function stageHas(step: any, keywords: string[]) {
  const text = [
    step?.skill,
    step?.theme,
    step?.pyramidTier,
    step?.businessScenario,
    targetRoleName.value,
    targetTechStack.value
  ].join(' ').toLowerCase()
  return keywords.some(keyword => text.includes(keyword.toLowerCase()))
}

function mergeItems(primary: string[], fallback: string[], limit = 4) {
  return Array.from(new Set([...primary, ...fallback].map(item => String(item).trim()).filter(Boolean))).slice(0, limit)
}

function compactItems(primary: string[], fallback: string[], limit = 2) {
  return mergeItems(primary, fallback, limit).map(item => compactText(item, '待确认', 28))
}

function strategyFocusForStage(index: number, isFinal: boolean) {
  const selected = selectedPlanModes.value.length ? selectedPlanModes.value : ['SKILL_GAP']
  let value = selected[index % selected.length]
  if (isFinal && selected.includes('PORTFOLIO')) value = 'PORTFOLIO'
  else if (index === 0 && selected.includes('SKILL_GAP')) value = 'SKILL_GAP'
  else if (selected.includes('ONBOARDING') && index <= 2) value = 'ONBOARDING'
  else if (selected.includes('PROMOTION') && index >= Math.max(2, pathSteps.value.length - 2)) value = 'PROMOTION'
  const label = planModes.find(item => item.value === value)?.label || '阶段培养'
  const meta = strategyRouteMeta[value] || strategyRouteMeta.SKILL_GAP
  const accentMap: Record<string, string> = {
    SKILL_GAP: 'blue',
    ONBOARDING: 'mint',
    PROMOTION: 'gold',
    PORTFOLIO: 'rose'
  }
  return {
    value,
    label,
    route: meta.route,
    acceptance: meta.acceptance,
    output: meta.output,
    accent: accentMap[value] || 'blue'
  }
}

function strategyStageBoard(step: any, skill: string, role: string, focus: ReturnType<typeof strategyFocusForStage>, defaults: any[]) {
  const taskItems = stepItems(step, 'weeklyTasks', undefined, 6)
  const deliveryItems = stepItems(step, 'deliverables', 'deliverable', 6)
  const assessmentItems = stepItems(step, 'assessment', undefined, 6)
  const kpiItems = stepItems(step, 'kpi', undefined, 6)
  if (focus.value === 'ONBOARDING') {
    return [
      { title: '场景适配', icon: 'briefcase', tone: 'blue', metric: 1, unit: '项', tag: '业务流程', items: compactItems(taskItems, [`绑定 ${role} 真实流程`, '明确上下游边界']) },
      { title: '协作规范', icon: 'route', tone: 'mint', metric: 3, unit: '类', tag: '研发制度', items: ['提交规范', '联调闭环'] },
      { title: '交付检查', icon: 'file', tone: 'gold', metric: Math.max(1, deliveryItems.length), unit: '件', tag: '试用证据', items: compactItems(deliveryItems, ['任务包', '复盘材料']) },
      { title: '导师复核', icon: 'audit', tone: 'rose', metric: Math.max(1, assessmentItems.length), unit: '条', tag: '转正门禁', items: compactItems(assessmentItems, ['规范达标', '问题可复盘']) }
    ]
  }
  if (focus.value === 'PROMOTION') {
    return [
      { title: '复杂任务', icon: 'target', tone: 'blue', metric: Math.max(1, taskItems.length), unit: '项', tag: '高阶场景', items: compactItems(taskItems, [`${skill} 复杂约束`, '异常边界']) },
      { title: '业务指标', icon: 'pulse', tone: 'mint', metric: Math.max(2, kpiItems.length), unit: '个', tag: '量化影响', items: compactItems(kpiItems, ['响应/稳定性', '指标来源']) },
      { title: '方案表达', icon: 'file', tone: 'gold', metric: Math.max(1, deliveryItems.length), unit: '份', tag: '评审材料', items: compactItems(deliveryItems, ['技术方案', '取舍说明']) },
      { title: '晋升评审', icon: 'audit', tone: 'rose', metric: Math.max(1, assessmentItems.length), unit: '轮', tag: '能力复评', items: compactItems(assessmentItems, ['独立拆解', '带动协作']) }
    ]
  }
  if (focus.value === 'PORTFOLIO') {
    return [
      { title: '作品主题', icon: 'briefcase', tone: 'blue', metric: 1, unit: '个', tag: '岗位作品', items: compactItems([`${role} 可演示作品`, '业务价值清晰'], taskItems) },
      { title: '可演示资产', icon: 'file', tone: 'mint', metric: Math.max(3, deliveryItems.length), unit: '件', tag: '作品集', items: compactItems(deliveryItems, ['项目仓库', '演示脚本']) },
      { title: '复盘材料', icon: 'book', tone: 'gold', metric: 3, unit: '类', tag: '可复盘', items: ['方案取舍', '指标结果'] },
      { title: '面试讲述', icon: 'audit', tone: 'rose', metric: Math.max(1, assessmentItems.length), unit: '轮', tag: '表达验证', items: compactItems(assessmentItems, ['业务价值', '架构追问']) }
    ]
  }
  return [
    { title: '缺口证据', icon: 'target', tone: 'blue', metric: 1, unit: '项', tag: 'P0 短板', items: [`补齐 ${compactText(skill, '技能', 12)}`, '影响匹配分'] },
    { title: '训练动作', icon: 'route', tone: 'mint', metric: Math.max(1, taskItems.length), unit: '项', tag: '专项训练', items: compactItems(taskItems, ['最小任务', '场景练习']) },
    { title: '验收门禁', icon: 'audit', tone: 'gold', metric: Math.max(1, assessmentItems.length), unit: '条', tag: '能力门禁', items: compactItems(assessmentItems, ['测评≥80', '导师确认']) },
    { title: '证据沉淀', icon: 'file', tone: 'rose', metric: Math.max(1, deliveryItems.length), unit: '件', tag: '可复核', items: compactItems(deliveryItems, defaults[1]?.items || ['专项作品', '复盘记录']) }
  ]
}

function stageBlueprint(step: any, index: number) {
  const skill = shortText(step?.skill, '当前能力')
  const role = targetRoleName.value
  const isFinal = index === pathSteps.value.length - 1 || stageHas(step, ['综合项目', '岗位作品'])
  const focus = strategyFocusForStage(index, isFinal)
  let packageBase: any

  if (isFinal) {
    packageBase = {
      scene: '端到端岗位作品交付',
      decision: '交付评审',
      score: 'Ready',
      outcome: `${role} 作品集交付`,
      kpis: ['核心链路可运行', '验收用例全通过', '演示材料可复盘', '岗位胜任复评≥80'],
      board: [
        { title: '业务任务', icon: 'briefcase', tone: 'blue', items: mergeItems(['锁定真实岗位场景和验收指标', '拆分核心功能、数据流和异常场景', '明确作品集展示对象和评审口径'], stepItems(step, 'weeklyTasks', undefined, 3)) },
        { title: '工程交付', icon: 'file', tone: 'mint', items: mergeItems(['可运行项目仓库', '部署说明与接口文档', '测试/评测报告', '3-5 分钟项目演示'], stepItems(step, 'deliverables', 'deliverable', 4)) },
        { title: '验收门禁', icon: 'audit', tone: 'gold', items: mergeItems(['核心用例通过率 100%', '关键指标有量化结果', '代码与文档可复现'], stepItems(step, 'assessment', undefined, 3)) },
        { title: '管理者关注', icon: 'pulse', tone: 'rose', items: ['是否能独立说明技术取舍', '是否能把功能价值讲清楚', '是否具备进入真实项目的协作节奏'] }
      ]
    }
  } else if (stageHas(step, ['restful', 'api', '接口'])) {
    packageBase = {
      scene: '接口契约与跨端联调',
      decision: '联调门禁',
      score: 'SLA',
      outcome: `${skill} 稳定接口契约`,
      kpis: ['OpenAPI 契约完整', '错误码覆盖≥8类', '核心接口 P95<200ms', '联调用例通过≥90%'],
      board: [
        { title: '业务任务', icon: 'briefcase', tone: 'blue', items: ['选择一个真实业务对象并设计新增/查询/更新接口', '定义请求参数、状态流转和边界条件', '与前端/调用方约定返回结构和错误码'] },
        { title: '工程交付', icon: 'file', tone: 'mint', items: mergeItems(['OpenAPI/Apifox 接口文档', 'Postman 或自动化接口用例', '鉴权、分页、幂等和异常处理示例'], stepItems(step, 'deliverables', 'deliverable', 3)) },
        { title: '验收门禁', icon: 'audit', tone: 'gold', items: mergeItems(['主流程与异常流程均可复现', '接口响应结构稳定', '联调问题有记录和关闭结论'], stepItems(step, 'assessment', undefined, 3)) },
        { title: '风险控制', icon: 'pulse', tone: 'rose', items: ['避免只写 Controller 无业务校验', '避免缺少错误码和日志追踪', '避免接口文档与实现不一致'] }
      ]
    }
  } else if (stageHas(step, ['mysql', 'postgresql', 'sql', '数据库'])) {
    packageBase = {
      scene: '数据建模与查询优化',
      decision: '数据门禁',
      score: 'EXPLAIN',
      outcome: `${skill} 数据能力验证`,
      kpis: ['表结构可解释', '慢查询可定位', '索引命中可证明', '事务边界清晰'],
      board: [
        { title: '业务任务', icon: 'database', tone: 'blue', items: ['围绕业务对象设计 3-5 张核心表', '梳理主键、外键、唯一约束和状态字段', '准备典型查询、统计和更新场景'] },
        { title: '工程交付', icon: 'file', tone: 'mint', items: mergeItems(['ER 图与建表 SQL', '核心查询脚本与样例数据', 'EXPLAIN 截图和索引优化记录'], stepItems(step, 'deliverables', 'deliverable', 3)) },
        { title: '验收门禁', icon: 'audit', tone: 'gold', items: mergeItems(['能说明范式与反范式取舍', '能定位一条慢 SQL 的原因', '事务一致性场景讲得清楚'], stepItems(step, 'assessment', undefined, 3)) },
        { title: '风险控制', icon: 'pulse', tone: 'rose', items: ['避免把学历/描述误当项目证据', '避免无索引全表扫描', '避免只会写查询不会解释业务含义'] }
      ]
    }
  } else if (stageHas(step, ['redis', '缓存'])) {
    packageBase = {
      scene: '缓存稳定性与降级演练',
      decision: '稳定性门禁',
      score: 'Guard',
      outcome: `${skill} 稳定性验证`,
      kpis: ['命中率目标明确', 'TTL 策略合理', '缓存异常可降级', '压测结果可复盘'],
      board: [
        { title: '业务任务', icon: 'briefcase', tone: 'blue', items: ['选择高频读取业务接口', '定义缓存 Key、TTL 和更新策略', '设计缓存穿透/击穿/雪崩处理方案'] },
        { title: '工程交付', icon: 'file', tone: 'mint', items: mergeItems(['缓存策略说明', 'Redis 接入代码', '压测对比数据', '异常降级记录'], stepItems(step, 'deliverables', 'deliverable', 4)) },
        { title: '验收门禁', icon: 'audit', tone: 'gold', items: mergeItems(['缓存命中率和延迟有对比', '异常场景不影响主流程', '能解释一致性边界'], stepItems(step, 'assessment', undefined, 3)) },
        { title: '风险控制', icon: 'pulse', tone: 'rose', items: ['避免所有数据一律缓存', '避免没有失效策略', '避免缓存与数据库数据不一致却无法解释'] }
      ]
    }
  } else if (stageHas(step, ['spring boot', 'java'])) {
    packageBase = {
      scene: stageHas(step, ['spring boot']) ? '服务模块工程化交付' : '后端编码基线校准',
      decision: '代码评审',
      score: 'Review',
      outcome: `${skill} 工程化交付`,
      kpis: ['代码规范通过', '单测覆盖核心分支', '日志异常完整', '能独立讲清实现'],
      board: [
        { title: '业务任务', icon: 'briefcase', tone: 'blue', items: ['选取一个岗位高频业务功能', '拆分实体、服务、校验和异常边界', '确认输入输出和数据落库规则'] },
        { title: '工程交付', icon: 'file', tone: 'mint', items: mergeItems(['最小可运行功能模块', '单元测试或接口测试', '代码评审记录', '问题修复清单'], stepItems(step, 'deliverables', 'deliverable', 4)) },
        { title: '验收门禁', icon: 'audit', tone: 'gold', items: mergeItems(['功能可运行且可复现', '核心异常有处理', '代码结构符合团队规范'], stepItems(step, 'assessment', undefined, 3)) },
        { title: '风险控制', icon: 'pulse', tone: 'rose', items: ['避免只完成 Demo 不考虑边界', '避免无测试无日志', '避免业务层和数据层职责混乱'] }
      ]
    }
  } else {
    packageBase = {
      scene: `${skill} 岗位专项能力验证`,
      decision: '专项验收',
      score: 'Gate',
      outcome: compactText(step?.businessScenario, `${skill} 专项能力验证`, 24),
      kpis: mergeItems(stepItems(step, 'kpi', undefined, 4), ['任务完成率 100%', '阶段测评≥80', '沉淀2类可复核证据'], 4),
      board: [
        { title: '业务任务', icon: 'briefcase', tone: 'blue', items: mergeItems(stepItems(step, 'weeklyTasks', undefined, 3), ['绑定一个岗位业务场景', '明确输入、输出和验收指标'], 4) },
        { title: '工程交付', icon: 'file', tone: 'mint', items: mergeItems(stepItems(step, 'deliverables', 'deliverable', 4), ['专项代码或实验记录', '复盘文档'], 4) },
        { title: '验收门禁', icon: 'audit', tone: 'gold', items: mergeItems(stepItems(step, 'assessment', undefined, 4), ['可独立讲解能力边界'], 4) },
        { title: '风险控制', icon: 'pulse', tone: 'rose', items: ['避免只学概念没有作品', '避免无法解释业务价值', '避免证据不可复核'] }
      ]
    }
  }

  return {
    ...packageBase,
    strategy: focus,
    board: strategyStageBoard(step, skill, role, focus, packageBase.board),
    narrative: [
      { label: '策略关联', value: `${focus.label} · ${focus.route}` },
      { label: '阶段产出', value: focus.output },
      { label: '验收口径', value: focus.acceptance }
    ],
    kpis: mergeItems(packageBase.kpis, [focus.acceptance, `${focus.output}可复盘`], 4).map(item => compactText(item, '指标待确认', 18))
  }
}

const enterpriseStagePackage = computed(() => {
  const step = selectedStage.value
  if (!step) return null
  return stageBlueprint(step, selectedStageIndex.value)
})
const stageOperatingView = computed(() => {
  const step = selectedStage.value
  const pack = enterpriseStagePackage.value
  if (!step || !pack) return null
  const board = Array.isArray(pack.board) ? pack.board : []
  const deliverables = stepItems(step, 'deliverables', 'deliverable', 5)
  const assessments = stepItems(step, 'assessment', undefined, 5)
  const score = stageScore(step)
  return {
    decisionCards: [
      { label: '策略', value: pack.strategy.label, unit: '', hint: pack.strategy.route },
      { label: '任务', value: board.reduce((sum: number, item: any) => sum + toNumber(item.metric, 0), 0), unit: '项', hint: '本层运营动作' },
      { label: '证据', value: Math.max(deliverables.length, pack.kpis.length), unit: '件', hint: pack.strategy.output },
      { label: '门禁', value: score, unit: '%', hint: pack.decision }
    ],
    flow: board.map((item: any, index: number) => ({
      ...item,
      index: String(index + 1).padStart(2, '0'),
      items: Array.isArray(item.items) ? item.items.slice(0, 2) : []
    })),
    gates: mergeItems(pack.kpis, assessments, 5).map(item => compactText(item, '验收项', 16)),
    assets: mergeItems(deliverables, board.flatMap((item: any) => item.items || []), 5).map(item => compactText(item, '交付资产', 18)),
    basis: compactText(step.rationale, '按岗位缺口、阶段依赖和企业策略动态编排', 44)
  }
})
const pyramidLayers = computed(() => pathSteps.value.map((step: any, index: number) => ({
  ...step,
  index,
  width: Math.max(48, 100 - index * 8),
  offset: Math.min(84, index * 14),
  status: stageStatusLabel(index),
  unlocked: isStageUnlocked(index),
  completed: isStageCompleted(index)
})))
const planDashboardMetrics = computed(() => [
  { label: tx('阶段层级', 'Stages'), value: pathSteps.value.length, unit: tx('层', ''), hint: tx('按岗位胜任路径逐层解锁', 'Unlocked by role-readiness path') },
  { label: tx('企业任务包', 'Enterprise Task Packages'), value: planTaskCount.value, unit: tx('项', 'items'), hint: tx('面向真实业务场景拆解', 'Decomposed for real business scenarios') },
  { label: tx('交付证据', 'Deliverable Evidence'), value: planDeliverableCount.value, unit: tx('件', 'items'), hint: tx('可进入作品集或转正评审', 'Can enter portfolio or probation review') },
  { label: tx('验收门禁', 'Acceptance Gates'), value: planAssessmentCount.value, unit: tx('条', 'items'), hint: tx('导师/系统复核标准', 'Mentor / system review criteria') }
])

const selectedModeObjects = computed(() => {
  const selected = planModes.filter(item => selectedPlanModes.value.includes(item.value))
  return selected.length ? selected : [planModes[0]]
})

function planModeLabel(value: string, fallback = '阶段培养') {
  if (value === 'SKILL_GAP') return tx('缺口补齐', 'Gap Closure')
  if (value === 'ONBOARDING') return tx('试用转正', 'Onboarding to Conversion')
  if (value === 'PROMOTION') return tx('晋升储备', 'Promotion Reserve')
  if (value === 'PORTFOLIO') return tx('作品集强化', 'Portfolio Strengthening')
  return tx(fallback, 'Stage Development')
}

function planModeDesc(value: string, fallback = '') {
  if (value === 'SKILL_GAP') return tx('优先处理岗位硬性技能缺口，适合招聘筛选后的短期培养。', 'Prioritize hard role-skill gaps for short-term development after recruiting screening.')
  if (value === 'ONBOARDING') return tx('强调基础规范、交付节奏和企业项目协作。', 'Emphasize engineering standards, delivery rhythm and enterprise collaboration.')
  if (value === 'PROMOTION') return tx('提升复杂项目、业务指标和架构表达能力。', 'Improve complex-project delivery, business metrics and architecture communication.')
  if (value === 'PORTFOLIO') return tx('把学习结果沉淀成可面试、可复盘、可展示的作品证据。', 'Convert learning outcomes into interview-ready, reviewable and demoable portfolio evidence.')
  return phrase(fallback)
}

function strategyRoute(value: string) {
  if (value === 'SKILL_GAP') return { route: tx('P0 缺口优先', 'P0 Gap First'), acceptance: tx('硬性技能通过专项验收', 'Hard skills pass focused acceptance'), output: tx('短板清单', 'Gap List') }
  if (value === 'ONBOARDING') return { route: tx('试用期上岗', 'Probation Onboarding'), acceptance: tx('企业规范与交付节奏达标', 'Enterprise standards and delivery rhythm met'), output: tx('上岗任务包', 'Onboarding Task Package') }
  if (value === 'PROMOTION') return { route: tx('晋升储备', 'Promotion Reserve'), acceptance: tx('复杂任务与业务指标可解释', 'Complex tasks and business metrics are explainable'), output: tx('晋升证据', 'Promotion Evidence') }
  if (value === 'PORTFOLIO') return { route: tx('作品集沉淀', 'Portfolio Building'), acceptance: tx('项目证据可演示、可复盘', 'Project evidence is demoable and reviewable'), output: tx('作品集', 'Portfolio') }
  return { route: tx('阶段培养', 'Stage Development'), acceptance: tx('按企业导师验收', 'Accepted by enterprise mentor'), output: tx('阶段产出', 'Stage Output') }
}

const selectedModeLabels = computed(() => selectedModeObjects.value.map(item => planModeLabel(item.value, item.label)).join(' / '))

const strategyRouteCards = computed(() => selectedModeObjects.value.map(item => ({
  ...item,
  label: planModeLabel(item.value, item.label),
  desc: planModeDesc(item.value, item.desc),
  ...strategyRoute(item.value)
})))

const planningEngineCards = computed(() => [
  {
    index: '01',
    title: '输入',
    value: `${candidateName.value} / ${targetRoleName.value}`,
    desc: '读取匹配报告、候选画像、技能缺口和岗位能力要求。'
  },
  {
    index: '02',
    title: '策略',
    value: selectedModeLabels.value,
    desc: '按企业培养目标动态重排路径优先级和训练口径。'
  },
  {
    index: '03',
    title: '输出',
    value: strategyRouteCards.value.map(item => item.output).join(' / '),
    desc: '生成阶段路径、任务包、交付证据和验收门禁。'
  }
])

const planReadinessItems = computed(() => [
  { label: '匹配报告', ok: Boolean(matchId.value) },
  { label: '候选画像', ok: Boolean(matchDetail.value?.person_name || selectedProfile.value) },
  { label: '缺口清单', ok: Boolean(missingSkills.value.length || matchedSkills.value.length) },
  { label: '阶段规则', ok: true }
])

const planReadinessPercent = computed(() => {
  const total = planReadinessItems.value.length
  const passed = planReadinessItems.value.filter(item => item.ok).length
  return Math.round((passed / Math.max(1, total)) * 100)
})

const gapBoard = computed(() => [
  {
    title: '优先补齐',
    count: missingSkills.value.length,
    tone: 'risk',
    icon: 'target',
    items: missingSkills.value.slice(0, 10),
    empty: '暂无硬性缺口，可进入项目化验证。'
  },
  {
    title: '可复用能力',
    count: matchedSkills.value.length,
    tone: 'good',
    icon: 'check',
    items: matchedSkills.value.slice(0, 10),
    empty: '等待匹配报告识别已具备技能。'
  },
  {
    title: '候选人技能库',
    count: resumeSkills.value.length,
    tone: 'info',
    icon: 'brain',
    items: resumeSkills.value.slice(0, 10),
    empty: '等待简历画像沉淀技能标签。'
  }
])

const allocationCards = computed(() => [
  { label: '岗位基线校准', percent: 25, gate: '准入门禁', desc: '确认候选人已具备目标岗位所需的基础规范、工具链和协作语境。' },
  { label: '核心能力训练', percent: 35, gate: '专项门禁', desc: '围绕岗位核心能力设计企业任务包，形成可验证的专项交付证据。' },
  { label: '业务场景交付', percent: 25, gate: '交付门禁', desc: '把能力迁移到真实业务场景，完成接口、数据、稳定性或项目化验收。' },
  { label: '上岗胜任评审', percent: 15, gate: '录用/转正门禁', desc: '沉淀作品集、复盘材料和面试表达，支撑企业最终用人决策。' }
])

const strategyList = computed(() => {
  const fromDetail = parseList(detail.value?.strategy)
  if (fromDetail.length) return fromDetail.slice(0, 4)
  const focus = missingSkills.value.slice(0, 3).join('、') || '岗位关键能力'
  return [
    `围绕 ${focus} 建立学习优先级`,
    '每个阶段必须产出可复核证据',
    '用项目任务验证技能迁移能力',
    '最终形成面试表达与补齐建议'
  ]
})

async function load(preferLatestPath = false) {
  const [matchRows, resumeRows, pathRows] = await Promise.all([api.matches(), api.resumes(), api.paths()])
  matches.value = matchRows as unknown as any[]
  resumes.value = resumeRows as unknown as any[]
  paths.value = pathRows as unknown as any[]
  if (!matchId.value && matches.value[0]) matchId.value = matches.value[0].id
  if ((preferLatestPath || !detail.value) && paths.value[0]) {
    detail.value = await api.path(paths.value[0].id)
    const modes = parseList(detail.value?.planModes ?? detail.value?.planMode)
    if (modes.length) selectedPlanModes.value = modes
  }
  await loadMatchDetail()
}

async function loadMatchDetail() {
  if (!matchId.value) {
    matchDetail.value = null
    return
  }
  try {
    matchDetail.value = await api.match(matchId.value)
  } catch {
    matchDetail.value = null
  }
}

async function refreshData() {
  refreshing.value = true
  try {
    await load(true)
    ElMessage.success('已更新最新匹配报告与学习路径列表')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    refreshing.value = false
  }
}

async function generate() {
  if (!matchId.value) {
    ElMessage.warning('请先完成人岗匹配')
    return
  }
  loading.value = true
  try {
    detail.value = await api.generatePath(matchId.value, PHASE_PLAN_WEEKS, PHASE_PLAN_HOURS, selectedPlanModes.value)
    selectedStageIndex.value = 0
    completedStageCount.value = 0
    await load()
    ElMessage.success(plannerIsAi.value ? 'AI 已生成动态培养方案' : '已生成规则兜底培养方案')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

async function optimizePath() {
  if (!detail.value?.id) {
    ElMessage.warning('请先生成培养方案')
    return
  }
  optimizing.value = true
  try {
    detail.value = await api.optimizePath(detail.value.id)
    const modes = parseList(detail.value?.planModes ?? detail.value?.planMode)
    if (modes.length) selectedPlanModes.value = modes
    selectedStageIndex.value = 0
    completedStageCount.value = 0
    await load()
    ElMessage.success(detail.value?.modelName === 'deterministic-fallback' ? '已完成结构化方案优化' : 'AI 已优化培养方案')
  } catch (err: any) {
    ElMessage.error(err.message)
  } finally {
    optimizing.value = false
  }
}

watch(matchId, loadMatchDetail)
watch(pathSteps, steps => {
  if (selectedStageIndex.value >= steps.length) selectedStageIndex.value = 0
  if (completedStageCount.value > steps.length) completedStageCount.value = 0
})

onMounted(load)
</script>

<template>
  <div class="learning-product-page">
    <section class="match-command-center learning-command-center" v-reveal>
      <div class="match-command-copy">
        <span class="match-kicker">{{ tx('学习规划智能体', 'Learning Path Agent') }}</span>
        <h1>{{ tx('AI 岗位培养方案规划台', 'AI Role Development Planning Console') }}</h1>
        <p>{{ tx('把人岗匹配报告、候选画像、岗位缺口和企业培养策略统一送入规划引擎，生成阶段路径、任务包和验收方案。', 'Feed matching reports, talent profiles, role gaps and enterprise training strategies into the planning engine to generate staged paths, task packages and acceptance plans.') }}</p>
        <div class="match-command-actions">
          <button class="button secondary" type="button" :disabled="refreshing" @click="refreshData">
            <AppIcon name="refresh" :size="16" />{{ refreshing ? tx('刷新中', 'Refreshing') : tx('同步最新匹配', 'Sync Latest Matches') }}
          </button>
          <button class="button primary" type="button" :disabled="loading || !matchId" @click="generate">
            <AppIcon name="route" :size="16" />{{ loading ? tx('AI 规划中', 'AI Planning') : tx('AI 动态生成方案', 'Generate AI Plan') }}
          </button>
        </div>
      </div>

      <aside class="match-readiness-card learning-command-card">
        <div class="match-readiness-head">
          <span>{{ tx('当前培养对象', 'Current Development Target') }}</span>
          <b>{{ candidateName }}</b>
        </div>
        <div class="match-route-line">
          <span>{{ candidateName }}</span>
          <AppIcon name="arrow" :size="15" />
          <span>{{ targetRoleName }}</span>
        </div>
        <div class="match-readiness-meter">
          <div>
            <span>{{ tx('AI 规划准备度', 'AI Planning Readiness') }}</span>
            <strong>{{ planReadinessPercent }}%</strong>
          </div>
          <ProgressBar :value="planReadinessPercent" :tone="planReadinessPercent >= 75 ? 'mint' : 'gold'" />
        </div>
        <div class="match-ready-list">
          <span v-for="item in planReadinessItems" :key="item.label" :class="{ done: item.ok }">
            <AppIcon :name="item.ok ? 'check' : 'focus'" :size="13" />{{ item.label }}
          </span>
        </div>
      </aside>
    </section>

    <section class="learning-command-grid">
      <article class="surface learning-control-card" v-reveal="40">
        <header class="surface-head">
          <div>
            <span class="eyebrow">{{ tx('规划输入', 'Planning Input') }}</span>
            <h2>{{ tx('锁定人岗样本', 'Lock Person-Job Sample') }}</h2>
            <p>{{ tx('选择一次人岗匹配报告，系统自动读取候选画像、岗位能力图谱和缺口清单。', 'Select a matching report; the system reads the talent profile, role capability graph and gap list automatically.') }}</p>
          </div>
        </header>
        <div class="surface-body learning-control-body">
          <label class="field">
            <span>{{ tx('匹配报告', 'Match Report') }}</span>
            <select v-model="matchId" class="select">
              <option v-for="item in matches" :key="item.id" :value="item.id">{{ phrase(item.person_name) }} → {{ phrase(item.role_name) }}（{{ item.overall_score }}）</option>
            </select>
          </label>
          <div class="learning-config-strip">
            <div><span>{{ tx('候选人', 'Candidate') }}</span><b>{{ phrase(candidateName) }}</b></div>
            <div><span>{{ tx('目标岗位', 'Target Role') }}</span><b>{{ phrase(targetRoleName) }}</b></div>
            <div><span>{{ tx('岗位方向', 'Role Domain') }}</span><b>{{ phrase(targetTechStack) }}</b></div>
          </div>
          <div class="learning-phase-rule">
            <article>
              <span>{{ tx('阶段制规则', 'Stage Rule') }}</span>
              <b>{{ tx('基础校准 → 专项训练 → 场景交付 → 胜任评审', 'Baseline Calibration → Focused Training → Scenario Delivery → Readiness Review') }}</b>
            </article>
            <article>
              <span>{{ tx('解锁方式', 'Unlock Rule') }}</span>
              <b>{{ tx('完成本层交付证据和验收门禁后进入下一层', 'Move to the next stage after deliverables and acceptance gates pass') }}</b>
            </article>
          </div>
        </div>
      </article>

      <article class="surface learning-priority-card" v-reveal="80">
        <header class="surface-head">
          <div>
            <span class="eyebrow">{{ tx('策略矩阵', 'Strategy Matrix') }}</span>
            <h2>{{ tx('选择企业培养策略', 'Select Enterprise Development Strategy') }}</h2>
            <p>{{ tx('支持多策略同时生效，大模型会据此调整推荐路径、任务深度和验收口径。', 'Multiple strategies can be active. The model adjusts path recommendation, task depth and acceptance criteria accordingly.') }}</p>
          </div>
          <span class="status-badge good">{{ tx('已选', 'Selected') }} {{ selectedModeObjects.length }} {{ tx('项', 'items') }}</span>
        </header>
        <div class="surface-body">
          <div class="learning-mode-selector strategy-mode-selector">
            <button
              v-for="mode in planModes"
              :key="mode.value"
              type="button"
              :class="{ active: selectedPlanModes.includes(mode.value) }"
              @click="togglePlanMode(mode.value)"
            >
              <span class="strategy-check"><AppIcon :name="selectedPlanModes.includes(mode.value) ? 'check' : 'plus'" :size="14" /></span>
              <b>{{ planModeLabel(mode.value, mode.label) }}</b>
              <small>{{ planModeDesc(mode.value, mode.desc) }}</small>
            </button>
          </div>
          <div class="learning-strategy-summary">
            <article>
              <span>{{ tx('目标组合', 'Target Mix') }}</span>
              <b>{{ selectedModeLabels }}</b>
            </article>
            <article>
              <span>{{ tx('规划机制', 'Planning Mechanism') }}</span>
              <b>{{ tx('大模型编排 · 阶段任务 · 交付证据 · 企业门禁', 'LLM orchestration · staged tasks · deliverable evidence · enterprise gates') }}</b>
            </article>
          </div>
          <div class="learning-strategy-routes">
            <article v-for="item in strategyRouteCards" :key="item.value">
              <span>{{ item.route }}</span>
              <b>{{ item.output }}</b>
              <small>{{ item.acceptance }}</small>
            </article>
          </div>
        </div>
      </article>
    </section>

    <section class="learning-ai-planner-strip" v-reveal="110">
      <article v-for="item in planningEngineCards" :key="item.index">
        <span>{{ item.index }} · {{ item.title }}</span>
        <b>{{ item.value }}</b>
        <small>{{ item.desc }}</small>
      </article>
    </section>

    <section class="surface learning-orchestration-board" v-reveal>
      <header class="surface-head">
        <div>
          <span class="eyebrow">{{ tx('培养路径编排驾驶舱', 'Development Path Orchestration Cockpit') }}</span>
          <h2>{{ tx('从岗位缺口到可验收产出', 'From Role Gaps to Acceptable Deliverables') }}</h2>
          <p>{{ tx('把匹配结果、能力资产和企业验收任务压缩成一张阶段制培养蓝图。', 'Compress matching results, capability assets and enterprise acceptance tasks into a staged development blueprint.') }}</p>
        </div>
        <div class="learning-board-badges">
          <span class="status-badge good">{{ matchScore ? `${formatNumber(matchScore, 1)}% ${tx('匹配', 'Match')}` : tx('待评估', 'Pending') }}</span>
          <span class="status-badge risk">{{ missingSkills.length }} {{ tx('项缺口', 'gaps') }}</span>
          <span class="status-badge">{{ plannerLabel }}</span>
        </div>
      </header>
      <div class="learning-orchestration-shell">
        <aside class="learning-orchestration-score">
          <span class="cockpit-label">{{ tx('方案态势', 'Plan Status') }}</span>
          <strong>{{ matchScore ? `${formatNumber(matchScore, 1)}%` : '--' }}</strong>
          <small>{{ tx('当前岗位匹配度', 'Current role match score') }}</small>
          <div class="cockpit-metrics">
            <div><b>{{ missingSkills.length }}</b><span>{{ tx('待补齐', 'Gaps') }}</span></div>
            <div><b>{{ reusableSkills.length }}</b><span>{{ tx('可复用', 'Reusable') }}</span></div>
            <div><b>{{ phaseStageCount }}</b><span>{{ tx('阶段门禁', 'Stage Gates') }}</span></div>
          </div>
          <div class="cockpit-mode-list">
            <span v-for="mode in selectedModeObjects" :key="mode.value">{{ planModeLabel(mode.value, mode.label) }}</span>
          </div>
        </aside>

        <div class="learning-orchestration-main">
          <div class="learning-flow-track">
            <article v-for="(item, index) in allocationCards" :key="item.label" class="learning-flow-step">
              <span class="flow-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <div>
                <div class="flow-step-head"><b>{{ item.label }}</b><strong>{{ item.gate }}</strong></div>
                <ProgressBar :value="item.percent" :tone="item.percent >= 40 ? 'mint' : 'gold'" />
                <p>{{ item.desc }}</p>
              </div>
            </article>
          </div>

          <div class="learning-cockpit-bottom">
            <article class="learning-queue-panel">
              <header>
                <span><AppIcon name="target" :size="17" /></span>
                <div><b>{{ tx('缺口处理队列', 'Gap Handling Queue') }}</b><small>{{ missingSkills.length ? tx('进入专项训练优先级', 'Prioritized for focused training') : tx('暂无硬性短板', 'No hard gaps') }}</small></div>
              </header>
              <div class="learning-training-queue compact">
                <div v-for="(skill, index) in missingSkills.slice(0, 5)" :key="skill">
                  <span>{{ String(index + 1).padStart(2, '0') }}</span>
                  <b>{{ phrase(skill) }}</b>
                  <em>{{ tx('补齐', 'Close') }}</em>
                </div>
                <div v-if="!missingSkills.length" class="empty-row">
                  <span>OK</span>
                  <b>{{ tx('可直接进入项目化验收路径', 'Ready for project-based acceptance path') }}</b>
                  <em>{{ tx('可验证', 'Verifiable') }}</em>
                </div>
              </div>
            </article>

            <article class="learning-asset-panel">
              <header>
                <span><AppIcon name="brain" :size="17" /></span>
                <div><b>{{ tx('能力资产复用', 'Capability Asset Reuse') }}</b><small>{{ tx('减少重复学习，优先转化为项目证据。', 'Reduce repeated learning and convert existing strengths into project evidence first.') }}</small></div>
              </header>
              <div class="skill-cloud learning-asset-cloud">
                <span v-for="skill in reusableSkills.slice(0, 14)" :key="skill" class="tag blue">{{ phrase(skill) }}</span>
                <span v-if="!reusableSkills.length" class="muted">{{ tx('等待匹配报告识别候选人能力资产', 'Waiting for the matching report to identify reusable capability assets') }}</span>
              </div>
            </article>
          </div>
        </div>
      </div>
    </section>

    <section v-if="detail" class="surface learning-plan-dashboard" v-reveal>
      <header class="surface-head learning-plan-dashboard-head">
        <div>
          <span class="eyebrow">{{ tx('培养方案输出', 'Development Plan Output') }}</span>
          <h2>{{ displayPlanTitle }}</h2>
          <p>{{ displayPlanObjective }}</p>
        </div>
        <div class="learning-plan-actions">
          <button class="button secondary" type="button" :disabled="optimizing" @click="optimizePath">
            <AppIcon name="spark" :size="15" />{{ optimizing ? tx('校准中', 'Calibrating') : tx('AI 校准当前方案', 'AI Calibrate Current Plan') }}
          </button>
          <div class="learning-path-meta">
            <span class="status-badge good">{{ pathSteps.length }} {{ tx('层阶段', 'stages') }}</span>
            <span class="status-badge">{{ tx('企业门禁制', 'Enterprise Gate System') }}</span>
            <span class="status-badge">{{ plannerLabel }}</span>
          </div>
        </div>
      </header>

      <div class="plan-dashboard-strip">
        <article v-for="item in planDashboardMetrics" :key="item.label">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}<em>{{ item.unit }}</em></strong>
          <small>{{ item.hint }}</small>
        </article>
      </div>

      <div class="pyramid-roadmap-board">
        <article class="pyramid-visual-card">
          <header>
            <div>
              <span class="eyebrow">{{ tx('晋升式路径', 'Promotion-style Path') }}</span>
              <h3>{{ phrase(targetRoleName) }} {{ tx('能力金字塔', 'Capability Pyramid') }}</h3>
              <p>{{ tx('按前置依赖逐层解锁，完成本层任务后进入下一层训练。', 'Unlock layers according to prerequisites and proceed after current-stage tasks are accepted.') }}</p>
            </div>
            <span class="status-badge good">{{ completedStageCount }} / {{ pathSteps.length }} {{ tx('已完成', 'completed') }}</span>
          </header>
          <div class="learning-pyramid" :style="{ '--layer-count': pathSteps.length }">
            <button
              v-for="layer in pyramidLayers"
              :key="`${layer.phase}-${layer.skill}`"
              type="button"
              class="pyramid-layer"
              :class="{ active: layer.index === selectedStageIndex, locked: !layer.unlocked, completed: layer.completed }"
              :style="{ '--layer-width': `${layer.width}%`, '--layer-order': layer.index }"
              @click="selectStage(layer.index)"
            >
              <span>{{ String(layer.index + 1).padStart(2, '0') }}</span>
              <div>
                <b>{{ phrase(layer.skill) }}</b>
                <small>{{ stageDisplayLabel(layer, layer.index) }}</small>
              </div>
              <em>{{ layer.status }}</em>
            </button>
          </div>
          <footer class="pyramid-legend">
            <span><i class="done"></i>{{ tx('已完成', 'Completed') }}</span>
            <span><i class="active"></i>{{ tx('当前层', 'Current') }}</span>
            <span><i></i>{{ tx('待解锁', 'Locked') }}</span>
          </footer>
        </article>

        <article v-if="selectedStage" class="pyramid-current-card">
          <header>
            <div>
              <span class="eyebrow">{{ phrase(shortText(selectedStage.pyramidTier, tx('当前层级', 'Current Tier'))) }}</span>
              <h3>{{ phrase(selectedStage.skill) }}</h3>
              <p>{{ tx('第', 'Stage') }} {{ selectedStageIndex + 1 }} {{ tx('阶段', '') }} · {{ phrase(shortText(selectedStage.theme, tx('专项训练', 'Focused Training'))) }}</p>
            </div>
            <strong>Gate {{ String(selectedStageIndex + 1).padStart(2, '0') }}</strong>
          </header>
          <div class="pyramid-unlock-rule">
            <AppIcon :name="isStageUnlocked(selectedStageIndex) ? 'check' : 'focus'" :size="16" />
            <div>
              <b>{{ stageStatusLabel(selectedStageIndex) }}</b>
              <span>{{ phrase(shortText(selectedStage.unlockRule, tx('上一层验收通过后解锁', 'Unlock after previous stage acceptance'))) }}</span>
            </div>
          </div>
          <div class="pyramid-stage-metrics">
            <article v-for="item in selectedStageMetrics" :key="item.label">
              <span>{{ item.label }}</span>
              <b>{{ item.value }}<em>{{ item.unit }}</em></b>
            </article>
          </div>
          <ProgressBar :value="stageScore(selectedStage)" :tone="stageTone(stageScore(selectedStage))" />
          <button
            class="button primary pyramid-unlock-button"
            type="button"
            :disabled="isStageCompleted(selectedStageIndex)"
            @click="completeSelectedStage"
          >
            <AppIcon name="check" :size="15" />{{ isStageCompleted(selectedStageIndex) ? tx('本层已完成', 'Stage Completed') : tx('完成本层并解锁下一层', 'Complete Stage and Unlock Next') }}
          </button>
        </article>
      </div>

      <div v-if="selectedStage" class="climb-stage-board">
        <article class="climb-altitude-card">
          <header>
            <div>
              <span class="eyebrow">{{ tx('登山路线', 'Climb Route') }}</span>
              <h3>{{ tx('第', 'Layer') }} {{ selectedStageIndex + 1 }} {{ tx('层高度', 'Altitude') }}</h3>
              <p>{{ tx('每一层能力金字塔对应一个山路高度，完成本层验收后继续上行。', 'Each pyramid layer maps to a climb altitude; move upward after acceptance.') }}</p>
            </div>
            <span class="status-badge good">{{ climbProgressPercent }}% {{ tx('进度', 'Progress') }}</span>
          </header>

          <div class="climb-mountain-map">
            <button
              v-for="layer in pyramidLayers"
              :key="`climb-${layer.phase}-${layer.skill}`"
              type="button"
              class="climb-altitude-node"
              :class="{ active: layer.index === selectedStageIndex, completed: layer.completed, locked: !layer.unlocked }"
              :style="{ '--step-offset': `${layer.offset}px` }"
              @click="selectStage(layer.index)"
            >
                <span>{{ String(layer.index + 1).padStart(2, '0') }}</span>
                <div>
                  <b>{{ phrase(layer.skill) }}</b>
                  <small>{{ stageDisplayLabel(layer, layer.index) }}</small>
                </div>
                <em>{{ layer.status }}</em>
              </button>
          </div>

          <footer>
            <span>{{ tx('当前能力', 'Current Capability') }}</span>
            <b>{{ phrase(selectedStage.skill) }}</b>
            <small>{{ phrase(shortText(selectedStage.dependency, tx('无强制前置能力', 'No mandatory prerequisite'))) }}</small>
          </footer>
        </article>

        <article class="climb-instruction-card" :class="enterpriseStagePackage ? `strategy-${enterpriseStagePackage.strategy.accent}` : ''">
          <header>
            <div>
              <span class="eyebrow">{{ phrase(enterpriseStagePackage?.strategy.label || shortText(selectedStage.pyramidTier, tx('当前层级', 'Current Tier'))) }}</span>
              <h3>{{ phrase(selectedStage.skill) }} · {{ phrase(enterpriseStagePackage?.strategy.route || tx('阶段规划', 'Stage Plan')) }}</h3>
              <p>{{ tx('第', 'Stage') }} {{ selectedStageIndex + 1 }} {{ tx('阶段', '') }} · {{ phrase(shortText(selectedStage.theme, tx('专项训练', 'Focused Training'))) }} · {{ phrase(enterpriseStagePackage?.strategy.acceptance) }}</p>
            </div>
            <span class="status-badge good">{{ stageStatusLabel(selectedStageIndex) }}</span>
          </header>

          <template v-if="enterpriseStagePackage && stageOperatingView">
            <div class="stage-os-hero">
              <div>
                <span>{{ phrase(enterpriseStagePackage.strategy.label) }} · {{ phrase(enterpriseStagePackage.strategy.route) }}</span>
                <b>{{ phrase(enterpriseStagePackage.outcome) }}</b>
              </div>
              <strong>{{ enterpriseStagePackage.score }}</strong>
            </div>

            <div class="stage-os-decision-grid">
              <article v-for="item in stageOperatingView.decisionCards" :key="item.label">
                <span>{{ item.label }}</span>
                <b>{{ item.value }}<em>{{ item.unit }}</em></b>
                <small>{{ item.hint }}</small>
              </article>
            </div>

            <div class="stage-os-flow">
              <article
                v-for="item in stageOperatingView.flow"
                :key="item.title"
                :class="`tone-${item.tone}`"
              >
                <span>{{ item.index }}</span>
                <div>
                  <b>{{ item.title }}</b>
                  <small>{{ item.tag }}</small>
                </div>
                <strong>{{ item.metric }}<em>{{ item.unit }}</em></strong>
                <ul>
                  <li v-for="text in item.items" :key="text">{{ phrase(text) }}</li>
                </ul>
              </article>
            </div>

            <div class="stage-os-console">
              <section>
                <header>
                  <span>{{ tx('验收闸口', 'Acceptance Gates') }}</span>
                  <b>{{ stageOperatingView.gates.length }} {{ tx('条', 'items') }}</b>
                </header>
                <div>
                  <em v-for="item in stageOperatingView.gates" :key="item">{{ phrase(item) }}</em>
                </div>
              </section>
              <section>
                <header>
                  <span>{{ tx('交付资产', 'Deliverable Assets') }}</span>
                  <b>{{ stageOperatingView.assets.length }} {{ tx('件', 'items') }}</b>
                </header>
                <div>
                  <em v-for="item in stageOperatingView.assets" :key="item">{{ phrase(item) }}</em>
                </div>
              </section>
              <section>
                <header>
                  <span>{{ tx('AI 编排依据', 'AI Orchestration Basis') }}</span>
                  <b>{{ tx('动态', 'Dynamic') }}</b>
                </header>
                <p>{{ phrase(stageOperatingView.basis) }}</p>
              </section>
            </div>
          </template>

          <div class="climb-unlock-note">
            <AppIcon name="check" :size="16" />
            <span>{{ phrase(shortText(selectedStage.unlockRule, tx('完成本层验收后解锁下一层', 'Unlock the next stage after current-stage acceptance'))) }}</span>
          </div>
        </article>
      </div>
    </section>

    <section v-else class="surface learning-standby-board" v-reveal>
      <div class="learning-standby-copy">
        <div class="inspector-icon"><AppIcon name="book" :size="22" /></div>
        <span class="eyebrow">{{ tx('待生成方案', 'Plan Pending') }}</span>
        <h2>{{ tx('先确认培养对象，系统将生成可验收路径', 'Confirm the development target to generate an acceptable path') }}</h2>
        <p>{{ tx('页面会保留企业端真正需要判断的信息：能力缺口、阶段门禁、交付证据、风险控制和面试验证口径，避免课程化配置干扰用人决策。', 'The page keeps enterprise decision information visible: capability gaps, stage gates, deliverable evidence, risk control and interview verification criteria.') }}</p>
      </div>
      <div class="learning-preview-lanes">
        <article v-for="item in allocationCards" :key="item.label">
          <div><b>{{ item.label }}</b><span>{{ item.percent }}%</span></div>
          <p>{{ item.desc }}</p>
        </article>
      </div>
      <div class="learning-preview-output">
        <article v-for="block in gapBoard" :key="block.title">
          <AppIcon :name="block.icon" :size="15" />
          <div>
            <b>{{ block.title }} · {{ block.count }} 项</b>
            <span>{{ block.items.length ? block.items.slice(0, 4).join('、') : block.empty }}</span>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>
