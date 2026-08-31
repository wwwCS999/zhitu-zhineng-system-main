import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '@/layout/AppShell.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '系统总览' } },
        { path: 'governance', redirect: '/parsing' },
        { path: 'parsing', component: () => import('@/views/JobParsingView.vue'), meta: { title: '数据治理与 JD 解析' } },
        { path: 'emerging', component: () => import('@/views/EmergingView.vue'), meta: { title: '岗位机会洞察' } },
        { path: 'forecast', redirect: '/emerging' },
        { path: 'evolution', redirect: '/graph' },
        { path: 'graph', component: () => import('@/views/GraphView.vue'), meta: { title: '能力图谱与演化' } },
        { path: 'matching', component: () => import('@/views/MatchingView.vue'), meta: { title: '画像匹配诊断' } },
        { path: 'learning', component: () => import('@/views/LearningView.vue'), meta: { title: '学习路径规划' } },
        { path: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '可信审核' } },
        { path: 'chat', component: () => import('@/views/ChatView.vue'), meta: { title: '智能体问答' } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior: (to) => to.hash
    ? ({ el: to.hash, top: 88, behavior: 'smooth' })
    : ({ top: 0, behavior: 'smooth' })
})

export default router
