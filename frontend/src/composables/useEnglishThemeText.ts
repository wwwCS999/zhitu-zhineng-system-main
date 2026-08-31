import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const TERM_MAP: Record<string, string> = {
  大模型应用: 'LLM Applications',
  人工智能: 'Artificial Intelligence',
  云原生: 'Cloud Native',
  大数据: 'Big Data',
  物联网: 'Internet of Things',
  数据基础: 'Data Infrastructure',
  后端开发: 'Backend Development',
  前端开发: 'Frontend Development',
  未分类: 'Uncategorized',
  数据治理智能体: 'Data Governance Agent',
  岗位洞察智能体: 'Job Insight Agent',
  能力图谱与演化智能体: 'Capability Graph & Evolution Agent',
  画像匹配智能体: 'Talent Matching Agent',
  学习规划智能体: 'Learning Path Agent',
  可信审核智能体: 'Trust Audit Agent',
  智能问答智能体: 'AI Q&A Agent',
  治理中: 'Running',
  治理完成: 'Completed',
  等待读取治理状态: 'Waiting for governance status',
  已完成: 'Completed',
  待审核: 'Pending Audit',
  已通过: 'Approved',
  已驳回: 'Rejected',
  需修改: 'Needs Revision',
  高风险: 'High Risk',
  中风险: 'Medium Risk',
  低风险: 'Low Risk',
  候选人: 'Candidate',
  目标年度: 'Target Year',
  全景: 'All',
  岗位: 'Roles',
  技能: 'Skills'
}

export function useEnglishThemeText() {
  const { locale } = useI18n()
  const isEnglish = computed(() => locale.value === 'en')

  function tx(zh: string, en: string) {
    return isEnglish.value ? en : zh
  }

  function term(value: unknown) {
    const text = String(value ?? '').trim()
    if (!isEnglish.value || !text) return text
    return TERM_MAP[text] || text
  }

  function phrase(value: unknown) {
    const text = String(value ?? '')
    if (!isEnglish.value || !text) return text
    return text
      .replaceAll('数据治理智能体', 'Data Governance Agent')
      .replaceAll('岗位洞察智能体', 'Job Insight Agent')
      .replaceAll('能力图谱与演化智能体', 'Capability Graph & Evolution Agent')
      .replaceAll('画像匹配智能体', 'Talent Matching Agent')
      .replaceAll('学习规划智能体', 'Learning Path Agent')
      .replaceAll('可信审核智能体', 'Trust Audit Agent')
      .replaceAll('大模型应用', 'LLM Applications')
      .replaceAll('人工智能', 'Artificial Intelligence')
      .replaceAll('云原生', 'Cloud Native')
      .replaceAll('大数据', 'Big Data')
      .replaceAll('物联网', 'Internet of Things')
      .replaceAll('数据基础', 'Data Infrastructure')
      .replaceAll('后端开发', 'Backend Development')
      .replaceAll('前端开发', 'Frontend Development')
      .replaceAll('条岗位', 'jobs')
      .replaceAll('可信度', 'Confidence')
      .replaceAll('证据', 'Evidence')
      .replaceAll('技能', 'Skills')
      .replaceAll('岗位', 'Roles')
  }

  return { isEnglish, tx, term, phrase }
}
