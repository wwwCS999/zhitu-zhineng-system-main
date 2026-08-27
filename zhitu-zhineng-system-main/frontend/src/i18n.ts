import { createI18n } from 'vue-i18n'

const zh = {
  app: {
    brand: '职途智配',
    brandSub: '岗位能力动态演化系统',
    agents: '核心智能体',
    overview: '总览',
    toggleLang: 'EN'
  },
  agent: {
    dataGovernance: '数据治理智能体',
    dataGovernanceDesc: '数据接入与治理',
    jobInsight: '岗位洞察智能体',
    jobInsightDesc: '新岗位发现与能力演化',
    capabilityGraph: '能力图谱与演化智能体',
    capabilityGraphDesc: '岗位技能全景图谱与能力动态演化',
    matching: '画像匹配智能体',
    matchingDesc: '简历解析与人岗匹配',
    learning: '学习规划智能体',
    learningDesc: '技能缺口与成长路径',
    trustAudit: '可信审核智能体',
    trustAuditDesc: '证据审核与幻觉防控'
  },
  common: {
    refresh: '刷新',
    refreshAll: '刷新全部',
    run: '运行完整流水线',
    running: '正在运行',
    loading: '正在加载',
    back: '返回'
  },
  dashboard: {
    eyebrow: '竞赛项目 · 岗位能力图谱 · 人岗匹配',
    title: '岗位能力图谱驱动的人岗匹配中台',
    subtitle: '融合 JD、简历、行业资料与岗位标准，动态识别岗位能力变化，输出可解释、可追溯、可落地的人岗匹配与成长建议。',
    governedJobs: '已治理岗位',
    skillRelations: '技能证据关系',
    rolesCandidates: '标准岗位 / 候选',
    matchReports: '匹配报告',
    agentCollaboration: '六智能体协同',
    agentCollaborationDesc: '从数据治理到可信审核的全流程闭环',
    agentsOnline: '多智能体在线协同',
    businessLoop: '完整业务闭环',
    businessLoopDesc: '点击任一阶段可直接进入对应功能页',
    techStackDist: '技术栈岗位分布',
    trustStatus: '可信运行状态',
    trustIndex: '数据可信指数',
    pendingAudits: '待审核事项',
    skillCount: '技能关系',
    roleCount: '标准岗位',
    yearTrend: '岗位年度数据量',
    topSkills: '高频技能点'
  }
}

const en = {
  app: {
    brand: 'Zhitu',
    brandSub: 'Job Capability Evolution System',
    agents: 'Core Agents',
    overview: 'Overview',
    toggleLang: '中'
  },
  agent: {
    dataGovernance: 'Data Governance Agent',
    dataGovernanceDesc: 'Data ingestion & governance',
    jobInsight: 'Job Insight Agent',
    jobInsightDesc: 'New role discovery & evolution',
    capabilityGraph: 'Capability Graph & Evolution Agent',
    capabilityGraphDesc: 'Role-skill graph & dynamic evolution',
    matching: 'Matching Agent',
    matchingDesc: 'Resume parsing & matching',
    learning: 'Learning Agent',
    learningDesc: 'Skill gaps & growth path',
    trustAudit: 'Trust Audit Agent',
    trustAuditDesc: 'Evidence audit & hallucination control'
  },
  common: {
    refresh: 'Refresh',
    refreshAll: 'Refresh All',
    run: 'Run Full Pipeline',
    running: 'Running',
    loading: 'Loading',
    back: 'Back'
  },
  dashboard: {
    eyebrow: 'Competition Project · Capability Graph · Matching',
    title: 'Job-Capability Graph Matching Console',
    subtitle: 'Fuse JD, resumes, industry reports and role standards to track capability evolution and deliver explainable, traceable person-job matching.',
    governedJobs: 'Governed Jobs',
    skillRelations: 'Skill Relations',
    rolesCandidates: 'Roles / Candidates',
    matchReports: 'Match Reports',
    agentCollaboration: 'Six-Agent Collaboration',
    agentCollaborationDesc: 'Full workflow from governance to trust audit',
    agentsOnline: 'Agents Online',
    businessLoop: 'Business Loop',
    businessLoopDesc: 'Click any stage to enter the feature',
    techStackDist: 'Tech Stack Distribution',
    trustStatus: 'Trust Status',
    trustIndex: 'Trust Index',
    pendingAudits: 'Pending Audits',
    skillCount: 'Skill Relations',
    roleCount: 'Roles',
    yearTrend: 'Jobs by Year',
    topSkills: 'Top Skills'
  }
}

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: 'zh',
  fallbackLocale: 'zh',
  messages: { zh, en }
})
