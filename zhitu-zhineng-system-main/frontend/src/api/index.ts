import http from './http'

export const api = {
  dashboard: (refresh = false) => http.get('/dashboard/overview', {
    params: { refresh },
    timeout: 300000
  }),
  runPipeline: () => http.post('/orchestrator/run-full-pipeline', null, {
    timeout: 120000
  }),

  documents: (limit = 100) => http.get('/data/documents', { params: { limit } }),
  quality: () => http.get('/data/quality'),
  importUrl: (url: string, sourceType = 'WEB') => http.post('/data/url', { url, sourceType }),
  uploadData: (file: File, type = 'REPORT') => {
    const form = new FormData()
    form.append('file', file)
    form.append('sourceType', type)
    return http.post('/data/upload', form)
  },
  importCsv: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/data/import/csv', form)
  },


  // 百万原始 JD：连续数据治理与结构化解析
  // 完整统计只在首次进入/手动刷新时读取；高频进度轮询使用 progress 快接口。
  rawGovernanceOverview: () => http.get('/raw-governance/overview', { timeout: 120000 }),
  rawGovernanceProgress: () => http.get('/raw-governance/progress', { timeout: 15000 }),
  rawGovernanceSchema: () => http.get('/raw-governance/schema', { timeout: 120000 }),
  rawGovernanceSamples: (limit = 80) => http.get('/raw-governance/samples', {
    params: { limit },
    timeout: 120000
  }),
  rawGovernanceRuns: (limit = 20) => http.get('/raw-governance/runs', {
    params: { limit },
    timeout: 120000
  }),
  startRawGovernance: (batchSize = 1000, reset = false) => http.post(
    '/raw-governance/start',
    null,
    { params: { batchSize, reset }, timeout: 120000 }
  ),
  pauseRawGovernance: () => http.post('/raw-governance/pause', null, { timeout: 120000 }),
  resumeRawGovernance: (batchSize = 1000) => http.post(
    '/raw-governance/resume',
    null,
    { params: { batchSize }, timeout: 120000 }
  ),
  governedJobs: (params: {
    page?: number
    size?: number
    year?: number | null
    keyword?: string
    state?: string
  } = {}) => http.get('/raw-governance/jobs', {
    params: {
      page: params.page || 1,
      size: params.size || 30,
      year: params.year || undefined,
      keyword: params.keyword?.trim() || undefined,
      state: params.state || 'ACTIVE'
    },
    timeout: 30000
  }),
  governedJob: (rawJobId: number) => http.get(`/raw-governance/jobs/${rawJobId}`, { timeout: 30000 }),
  updateGovernedJob: (rawJobId: number, payload: any) => http.put(`/raw-governance/jobs/${rawJobId}`, payload, { timeout: 120000 }),
  deleteGovernedJob: (rawJobId: number) => http.delete(`/raw-governance/jobs/${rawJobId}`, { timeout: 120000 }),
  addGovernedSkill: (rawJobId: number, payload: any) => http.post(`/raw-governance/jobs/${rawJobId}/skills`, payload, { timeout: 120000 }),
  deleteGovernedSkill: (rawJobId: number, skillId: number) => http.delete(`/raw-governance/jobs/${rawJobId}/skills/${skillId}`, { timeout: 120000 }),


  jobs: (limit = 200) => http.get('/jobs', { params: { limit } }),
  parseAll: () => http.post('/jobs/parse-all'),
  parseJobText: (title: string, description: string) => http.post('/jobs/parse-text', { title, description }),
  parserEvaluation: () => http.get('/jobs/parser-evaluation'),
  runParserEvaluation: () => http.post('/jobs/parser-evaluation/run', null, { timeout: 120000 }),
  role: (id: number) => http.get(`/jobs/roles/${id}`),

  emergingYears: () => http.get('/emerging/years', { timeout: 120000 }),
  candidates: (targetYear: number) => http.get('/emerging/candidates', { params: { targetYear }, timeout: 120000 }),
  discover: (targetYear: number) => http.post('/emerging/discover', null, { params: { targetYear }, timeout: 300000 }),

  // 百万历史岗位：训练/测试划分 + 年度滚动预测验证
  temporalOverview: () => http.get('/temporal/overview', { timeout: 120000 }),
  temporalYears: () => http.get('/temporal/years', { timeout: 120000 }),
  prepareTemporalHoldout: (
    reset = false,
    year = new Date().getFullYear(),
    size = 1000
  ) => http.post(
    '/temporal/holdout/prepare',
    { year, size, seed: `zhitu-${year}-v1`, reset },
    { timeout: 120000 }
  ),
  temporalHoldoutSample: (limit = 20) => http.get('/temporal/holdout/sample', {
    params: { limit },
    timeout: 120000
  }),
  runTemporalBacktest: (
    startYear = 2020,
    endYear = new Date().getFullYear(),
    topK = 30,
    minSupport = 3
  ) => http.post(
    '/temporal/backtest',
    { startYear, endYear, topK, minSupport },
    { timeout: 300000 }
  ),
  temporalRuns: (limit = 60) => http.get('/temporal/runs', {
    params: { limit },
    timeout: 120000
  }),
  temporalRun: (runId: string) => http.get(`/temporal/runs/${runId}`, { timeout: 120000 }),

  evolutions: () => http.get('/evolution/events'),
  analyzeEvolution: () => http.post('/evolution/analyze'),

  panorama: (techStack = '', level = '', limit = 500, minEvidence = 1, refresh = false) => http.get('/graph/panorama', {
    params: {
      techStack: techStack || undefined,
      level: level || undefined,
      limit,
      minEvidence,
      refresh
    },
    timeout: 600000
  }),
  roles: () => http.get('/graph/roles', { timeout: 120000 }),

  resumes: () => http.get('/resumes'),
  parseResume: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/resumes/parse', form, { timeout: 120000 })
  },
  parseResumeImage: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/resumes/parse-image', form, { timeout: 180000 })
  },
  parseResumeText: (text: string) => http.post('/resumes/parse-text', { text }, { timeout: 120000 }),

  matches: () => http.get('/matches'),
  analyzeMatch: (resumeId: number, roleId: number) => http.post('/matches/analyze', { resumeId, roleId }),
  match: (id: number) => http.get(`/matches/${id}`),

  paths: () => http.get('/learning'),
  generatePath: (matchId: number, weeks = 12, hoursPerWeek = 8, planModes: string[] | string = ['SKILL_GAP']) => {
    const modes = Array.isArray(planModes) ? planModes : [planModes]
    return http.post('/learning/generate', {
      matchId,
      weeks,
      hoursPerWeek,
      planMode: modes[0] || 'SKILL_GAP',
      planModes: modes
    })
  },
  path: (id: number) => http.get(`/learning/${id}`),
  optimizePath: (id: number) => http.post(`/learning/${id}/optimize`, {}, { timeout: 120000 }),

  pending: () => http.get('/audits/pending'),
  auditHistory: () => http.get('/audits/history'),
  decide: (type: string, id: number, action: string, comment = '', patch = {}, reviewer = '竞赛管理员') => http.post(
    `/audits/${type}/${id}/decision`,
    { action, reviewer, comment, patch }
  ),

  chat: (message: string, sessionId = 'demo') => http.post(
    '/agent/chat',
    { message, sessionId },
    { timeout: 120000 }
  ),
  agentStatus: () => http.get('/agent/status'),
  runs: () => http.get('/agent/runs')
}
