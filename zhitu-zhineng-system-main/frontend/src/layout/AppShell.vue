<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import FloatingAgent from '@/components/FloatingAgent.vue'

const route = useRoute()
const { t, locale } = useI18n()
const mobileOpen = ref(false)
const pageTitle = computed(() => String(route.meta.title || t('app.brand')))

function toggleLocale() {
  locale.value = locale.value === 'zh' ? 'en' : 'zh'
}

const navigation = [
  { to: '/', label: 'app.overview', desc: '项目驾驶舱', icon: 'home', tag: 'Overview' },
  { to: '/parsing', label: 'agent.dataGovernance', desc: '数据接入与治理', icon: 'database', tag: 'Data' },
  { to: '/emerging', label: 'agent.jobInsight', desc: '新岗位发现与验证', icon: 'spark', tag: 'Insight' },
  { to: '/graph', label: 'agent.capabilityGraph', desc: '岗位能力图谱', icon: 'network', tag: 'Graph' },
  { to: '/matching', label: 'agent.matching', desc: '画像与岗位匹配', icon: 'match', tag: 'Match' },
  { to: '/learning', label: 'agent.learning', desc: '技能缺口补齐', icon: 'route', tag: 'Learn' },
  { to: '/audit', label: 'agent.trustAudit', desc: '证据与可信审核', icon: 'audit', tag: 'Trust' },
  { to: '/chat', label: '智能问答', desc: '图谱 RAG 助手', icon: 'chat', tag: 'Ask' }
]

const activeModuleInfo = computed(() => navigation.find((item) => item.to === route.path) ?? navigation[0])

watch(() => route.fullPath, () => {
  mobileOpen.value = false
})
</script>

<template>
  <div class="app-shell">
    <div class="workspace-frame">
      <aside class="workspace-sidebar" :class="{ 'is-open': mobileOpen }" aria-label="系统模块导航">
        <RouterLink to="/" class="brand sidebar-brand" aria-label="返回系统总览">
          <span class="brand-mark">职</span>
          <span class="brand-copy">
            <b>{{ t('app.brand') }}</b>
            <small>{{ t('app.brandSub') }}</small>
          </span>
        </RouterLink>

        <div class="sidebar-section-label">核心工作流</div>
        <nav class="sidebar-nav" aria-label="核心工作流">
          <RouterLink
            v-for="item in navigation"
            :key="item.to"
            :to="item.to"
            class="sidebar-link"
          >
            <span class="sidebar-link-icon"><AppIcon :name="item.icon" :size="17" /></span>
            <span class="sidebar-link-copy">
              <b>{{ item.label.startsWith('agent.') || item.label.startsWith('app.') ? t(item.label) : item.label }}</b>
              <small>{{ item.desc }}</small>
            </span>
            <em>{{ item.tag }}</em>
          </RouterLink>
        </nav>

        <div class="sidebar-foot-card">
          <span class="agent-dot" />
          <div>
            <b>6 个智能体在线</b>
            <small>治理、探新、图谱、匹配、学习、审核协同运行</small>
          </div>
        </div>
      </aside>

      <button
        v-if="mobileOpen"
        class="sidebar-scrim"
        type="button"
        aria-label="关闭导航"
        @click="mobileOpen = false"
      />

      <div class="workspace-content">
        <header class="workspace-topbar">
          <button class="mobile-menu" type="button" aria-label="打开导航" @click="mobileOpen = !mobileOpen">
            <AppIcon :name="mobileOpen ? 'close' : 'menu'" :size="20" />
          </button>
          <div class="topbar-title">
            <span class="agent-badge">
              <AppIcon :name="activeModuleInfo.icon" :size="14" />
              {{ activeModuleInfo.label.startsWith('agent.') || activeModuleInfo.label.startsWith('app.') ? t(activeModuleInfo.label) : activeModuleInfo.label }}
            </span>
            <h1>{{ pageTitle }}</h1>
            <p>面向信息技术岗位的多源数据治理、能力图谱构建、动态演化分析与人岗精准匹配。</p>
          </div>
          <div class="topbar-actions">
            <RouterLink to="/matching" class="button primary topbar-cta">
              <AppIcon name="match" :size="15" /> 开始匹配
            </RouterLink>
            <button class="locale-toggle" type="button" @click="toggleLocale">
              {{ t('app.toggleLang') }}
            </button>
          </div>
        </header>

        <main id="main-content" class="page-main">
          <RouterView v-slot="{ Component }">
            <Transition name="page" mode="out-in">
              <component :is="Component" />
            </Transition>
          </RouterView>
        </main>

        <footer class="app-footer">
          <span>{{ t('app.brandSub') }} · 多源异构数据驱动岗位和能力图谱构建与动态演化分析研究</span>
          <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">鄂ICP备2026045194号</a>
        </footer>
      </div>
    </div>

    <FloatingAgent />
  </div>
</template>
