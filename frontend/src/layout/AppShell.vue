<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import FloatingAgent from '@/components/FloatingAgent.vue'

const route = useRoute()
const { t, locale } = useI18n()
const mobileOpen = ref(false)

const savedLocale = localStorage.getItem('zhitu-locale')
if (savedLocale === 'zh' || savedLocale === 'en') {
  locale.value = savedLocale
}

function toggleLocale() {
  const nextLocale = locale.value === 'zh' ? 'en' : 'zh'
  locale.value = nextLocale
  localStorage.setItem('zhitu-locale', nextLocale)
}

const navigation = [
  { to: '/', label: 'app.overview', desc: 'app.overviewDesc', icon: 'home', tag: 'Overview' },
  { to: '/parsing', label: 'agent.dataGovernance', desc: 'agent.dataGovernanceDesc', icon: 'database', tag: 'Data' },
  { to: '/emerging', label: 'agent.jobInsight', desc: 'agent.jobInsightDesc', icon: 'spark', tag: 'Insight' },
  { to: '/graph', label: 'agent.capabilityGraph', desc: 'agent.capabilityGraphDesc', icon: 'network', tag: 'Graph' },
  { to: '/matching', label: 'agent.matching', desc: 'agent.matchingDesc', icon: 'match', tag: 'Match' },
  { to: '/learning', label: 'agent.learning', desc: 'agent.learningDesc', icon: 'route', tag: 'Learn' },
  { to: '/audit', label: 'agent.trustAudit', desc: 'agent.trustAuditDesc', icon: 'audit', tag: 'Trust' },
  { to: '/chat', label: 'app.chat', desc: 'app.chatDesc', icon: 'chat', tag: 'Ask' }
]

const activeModuleInfo = computed(() => navigation.find((item) => item.to === route.path) ?? navigation[0])
const activeModuleName = computed(() => t(activeModuleInfo.value.label))
const pageTitle = computed(() => locale.value === 'en' ? activeModuleName.value : String(route.meta.title || activeModuleName.value))

watch(() => route.fullPath, () => {
  mobileOpen.value = false
})
</script>

<template>
  <div class="app-shell" :class="`locale-${locale}`">
    <div class="workspace-frame">
      <aside class="workspace-sidebar" :class="{ 'is-open': mobileOpen }" :aria-label="t('app.workflow')">
        <RouterLink to="/" class="brand sidebar-brand" :aria-label="t('app.backHome')">
          <span class="brand-mark">{{ t('app.brandMark') }}</span>
          <span class="brand-copy">
            <b>{{ t('app.brand') }}</b>
            <small>{{ t('app.brandSub') }}</small>
          </span>
        </RouterLink>

        <div class="sidebar-section-label">{{ t('app.workflow') }}</div>
        <nav class="sidebar-nav" :aria-label="t('app.workflow')">
          <RouterLink
            v-for="item in navigation"
            :key="item.to"
            :to="item.to"
            class="sidebar-link"
          >
            <span class="sidebar-link-icon"><AppIcon :name="item.icon" :size="17" /></span>
            <span class="sidebar-link-copy">
              <b>{{ item.label.startsWith('agent.') || item.label.startsWith('app.') ? t(item.label) : item.label }}</b>
              <small>{{ t(item.desc) }}</small>
            </span>
            <em>{{ item.tag }}</em>
          </RouterLink>
        </nav>

        <div class="sidebar-foot-card">
          <span class="agent-dot" />
          <div>
            <b>{{ t('app.agentsOnline') }}</b>
            <small>{{ t('app.agentsOnlineDesc') }}</small>
          </div>
        </div>
      </aside>

      <button
        v-if="mobileOpen"
        class="sidebar-scrim"
        type="button"
        :aria-label="t('app.closeNav')"
        @click="mobileOpen = false"
      />

      <div class="workspace-content">
        <header class="workspace-topbar">
          <button class="mobile-menu" type="button" :aria-label="t('app.openNav')" @click="mobileOpen = !mobileOpen">
            <AppIcon :name="mobileOpen ? 'close' : 'menu'" :size="20" />
          </button>
          <div class="topbar-title">
            <span class="agent-badge">
              <AppIcon :name="activeModuleInfo.icon" :size="14" />
              {{ activeModuleInfo.label.startsWith('agent.') || activeModuleInfo.label.startsWith('app.') ? t(activeModuleInfo.label) : activeModuleInfo.label }}
            </span>
            <h1>{{ pageTitle }}</h1>
            <p>{{ t('app.topbarDesc') }}</p>
          </div>
          <div class="topbar-actions">
            <RouterLink to="/matching" class="button primary topbar-cta">
              <AppIcon name="match" :size="15" /> {{ t('app.startMatching') }}
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
          <span>{{ t('app.brandSub') }} · {{ t('app.footerResearch') }}</span>
          <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">{{ t('app.filing') }}</a>
        </footer>
      </div>
    </div>

    <FloatingAgent />
  </div>
</template>
