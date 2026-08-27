<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import RichAnswer from '@/components/RichAnswer.vue'

type FloatingMessage = {
  role: 'user' | 'assistant'
  text: string
  confidence?: number
  agents?: string[]
}

const route = useRoute()
const router = useRouter()
const opened = ref(false)
const busy = ref(false)
const input = ref('')
const messageBox = ref<HTMLElement>()
const panel = ref<HTMLElement>()
const modelStatus = ref<any>({ enabled: false, model: '', mode: 'LOADING' })
const position = ref({ x: 0, y: 0 })
const dragging = ref(false)

const sectionSuggestions: Record<string, string[]> = {
  '/': ['概括当前系统的数据状态', '哪些能力分析最值得关注？'],
  '/parsing': ['当前岗位数据治理质量如何？', '2025 年有效岗位有多少？'],
  '/emerging': ['当前发现了哪些新岗位？', '所选年份的岗位探新准确率如何？'],
  '/evolution': ['Java 岗位新增了哪些技能？', '技能需求发生了什么变化？'],
  '/graph': ['解释当前岗位能力图谱', '岗位与技能之间有什么关系？'],
  '/matching': ['如何理解岗位匹配分数？', '当前主要技能缺口是什么？'],
  '/learning': ['如何根据技能差距生成学习路径？', '学习计划应该如何排序？'],
  '/audit': ['当前有哪些结果需要审核？', '如何判断证据是否可信？'],
  '/chat': ['当前发现了哪些新岗位？', '查询 2025 年热门岗位排行']
}

const pageTitle = computed(() => String(route.meta.title || '系统总览'))
const suggestions = computed(() => sectionSuggestions[route.path] || sectionSuggestions['/'])
const statusText = computed(() => modelStatus.value.enabled
  ? `${modelStatus.value.model || '大模型'} · 数据证据模式`
  : '数据库检索模式')

function createSessionId() {
  const id = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `float-${Date.now()}-${Math.random().toString(16).slice(2)}`
  sessionStorage.setItem('zhitu-floating-agent-session-id', id)
  return id
}

const sessionId = ref(sessionStorage.getItem('zhitu-floating-agent-session-id') || createSessionId())
const messages = ref<FloatingMessage[]>([
  {
    role: 'assistant',
    text: '你好，我是小职。可以直接问我当前板块的数据、结论、操作方法或岗位趋势，我会结合系统数据库回答。'
  }
])

function clampPosition() {
  if (window.innerWidth <= 680) return
  const width = panel.value?.offsetWidth || 430
  const height = panel.value?.offsetHeight || 650
  position.value = {
    x: Math.max(12, Math.min(position.value.x, window.innerWidth - width - 12)),
    y: Math.max(84, Math.min(position.value.y, window.innerHeight - height - 12))
  }
}

async function open() {
  const saved = localStorage.getItem('zhitu-floating-agent-position')
  if (saved) {
    try { position.value = JSON.parse(saved) } catch { /* ignore invalid local preference */ }
  } else {
    position.value = {
      x: Math.max(12, window.innerWidth - 430 - 24),
      y: Math.max(84, window.innerHeight - Math.min(680, window.innerHeight - 98) - 24)
    }
  }
  opened.value = true
  await nextTick()
  clampPosition()
  await scrollBottom()
}

function close() {
  opened.value = false
}

function resetConversation() {
  sessionId.value = createSessionId()
  messages.value = [{
    role: 'assistant',
    text: `已开启新对话。你现在位于【${pageTitle.value}】，可以继续询问这个板块的数据和功能。`
  }]
  input.value = ''
}

async function scrollBottom() {
  await nextTick()
  messageBox.value?.scrollTo({ top: messageBox.value.scrollHeight, behavior: 'smooth' })
}

async function send(text?: string) {
  const question = (text || input.value).trim()
  if (!question || busy.value) return
  messages.value.push({ role: 'user', text: question })
  input.value = ''
  busy.value = true
  await scrollBottom()
  const contextualQuestion = `当前所在系统板块：${pageTitle.value}（${route.path}）。请优先结合该板块对应的系统数据与功能回答。用户问题：${question}`
  try {
    const result: any = await api.chat(contextualQuestion, sessionId.value)
    messages.value.push({
      role: 'assistant',
      text: result.answer,
      confidence: result.confidence,
      agents: result.agents
    })
  } catch (error: any) {
    messages.value.push({ role: 'assistant', text: `请求暂时没有完成：${error.message || '请稍后重试'}` })
  } finally {
    busy.value = false
    await scrollBottom()
  }
}

let dragStart = { pointerX: 0, pointerY: 0, panelX: 0, panelY: 0 }

function startDrag(event: PointerEvent) {
  if (window.innerWidth <= 680 || (event.target as HTMLElement).closest('button')) return
  dragging.value = true
  dragStart = {
    pointerX: event.clientX,
    pointerY: event.clientY,
    panelX: position.value.x,
    panelY: position.value.y
  }
  window.addEventListener('pointermove', moveDrag)
  window.addEventListener('pointerup', stopDrag, { once: true })
}

function moveDrag(event: PointerEvent) {
  if (!dragging.value) return
  position.value = {
    x: dragStart.panelX + event.clientX - dragStart.pointerX,
    y: dragStart.panelY + event.clientY - dragStart.pointerY
  }
  clampPosition()
}

function stopDrag() {
  dragging.value = false
  window.removeEventListener('pointermove', moveDrag)
  localStorage.setItem('zhitu-floating-agent-position', JSON.stringify(position.value))
}

function openFullChat() {
  close()
  router.push('/chat')
}

watch(() => route.fullPath, () => nextTick(clampPosition))

onMounted(async () => {
  window.addEventListener('resize', clampPosition)
  try { modelStatus.value = await api.agentStatus() } catch { modelStatus.value = { enabled: false } }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', clampPosition)
  window.removeEventListener('pointermove', moveDrag)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="zhidian-launcher">
      <button v-if="!opened" class="zhidian-launcher" type="button" aria-label="打开小职智能问答" @click="open">
        <span class="launcher-orbit"><i /><AppIcon name="spark" :size="24" /></span>
        <span><b>小职</b><small>随时问数据</small></span>
        <span class="launcher-pulse" />
      </button>
    </Transition>

    <Transition name="zhidian-panel">
      <section
        v-if="opened"
        ref="panel"
        class="zhidian-panel"
        :class="{ dragging }"
        :style="{ left: `${position.x}px`, top: `${position.y}px` }"
        aria-label="小职智能问答窗口"
      >
        <header class="zhidian-head" @pointerdown="startDrag">
          <span class="drag-handle" />
          <div class="zhidian-identity">
            <span class="zhidian-logo"><AppIcon name="spark" :size="21" /></span>
            <div><b>小职</b><small>沿着当前数据继续理解</small></div>
          </div>
          <span class="zhidian-mode"><i /> AI 助教</span>
          <div class="zhidian-head-actions">
            <button type="button" title="新对话" aria-label="新对话" @click="resetConversation"><AppIcon name="plus" :size="17" /></button>
            <button type="button" title="打开完整问答页" aria-label="打开完整问答页" @click="openFullChat"><AppIcon name="chat" :size="16" /></button>
            <button type="button" title="收起" aria-label="收起" @click="close"><AppIcon name="close" :size="17" /></button>
          </div>
        </header>

        <div class="zhidian-context">
          <span><AppIcon name="focus" :size="14" /> 正在理解</span>
          <b>{{ pageTitle }}</b>
          <small>{{ statusText }}</small>
        </div>

        <div ref="messageBox" class="zhidian-messages">
          <div v-if="messages.length === 1" class="zhidian-suggestions">
            <span>你可以接着问</span>
            <button v-for="suggestion in suggestions" :key="suggestion" type="button" @click="send(suggestion)">
              {{ suggestion }}<AppIcon name="arrow" :size="13" />
            </button>
          </div>

          <article v-for="(message, index) in messages" :key="index" class="zhidian-message" :class="message.role">
            <div v-if="message.role === 'assistant'" class="zhidian-speaker">
              <span><AppIcon name="spark" :size="13" /></span><b>小职</b>
              <em v-if="message.confidence">可信度 {{ Math.round(message.confidence * 100) }}%</em>
            </div>
            <div class="zhidian-bubble">
              <RichAnswer v-if="message.role === 'assistant'" :content="message.text" compact />
              <span v-else>{{ message.text }}</span>
            </div>
          </article>

          <article v-if="busy" class="zhidian-message assistant">
            <div class="zhidian-speaker"><span><AppIcon name="spark" :size="13" /></span><b>小职</b></div>
            <div class="zhidian-bubble zhidian-thinking"><i /><i /><i /><span>正在查询数据库并组织答案</span></div>
          </article>
        </div>

        <footer class="zhidian-composer">
          <textarea
            v-model="input"
            rows="2"
            :placeholder="`询问${pageTitle}相关的数据或操作…`"
            @keydown.enter.exact.prevent="send()"
            @keydown.shift.enter.stop
          />
          <button type="button" :disabled="busy || !input.trim()" aria-label="发送问题" @click="send()">
            <AppIcon name="send" :size="17" />
          </button>
          <small>Enter 发送 · Shift + Enter 换行</small>
        </footer>
      </section>
    </Transition>
  </Teleport>
</template>

<style scoped>
.zhidian-launcher { position: fixed; right: 24px; bottom: 26px; z-index: 180; min-width: 154px; height: 66px; padding: 8px 16px 8px 8px; display: flex; align-items: center; gap: 10px; border: 1px solid #e7d39f; border-radius: 20px 20px 8px 20px; color: #33483f; background: linear-gradient(130deg, #fffaf0 0%, #f6edd7 58%, #f2e5c5 100%); box-shadow: 0 18px 46px rgba(92,76,42,.18), 0 0 0 5px rgba(255,253,247,.76); transition: transform 220ms ease, box-shadow 220ms ease, filter 220ms ease; }
.zhidian-launcher:hover { transform: translateY(-4px); filter: brightness(1.015); box-shadow: 0 24px 58px rgba(92,76,42,.23), 0 0 0 6px rgba(248,240,220,.82); }
.launcher-orbit { width: 48px; height: 48px; position: relative; display: grid; place-items: center; border: 1px solid #d9bd78; border-radius: 50% 50% 50% 8px; color: #80642a; background: rgba(255,254,249,.92); transform: rotate(-45deg); }
.launcher-orbit > * { transform: rotate(45deg); }
.launcher-orbit i { position: absolute; width: 22px; height: 22px; border: 1px solid #e4cf9b; border-radius: 50%; }
.zhidian-launcher > span:nth-child(2) { display: grid; gap: 2px; text-align: left; }
.zhidian-launcher b { font-size: 17px; letter-spacing: .04em; }
.zhidian-launcher small { color: #74684d; font-size: 9px; }
.launcher-pulse { width: 7px; height: 7px; margin-left: auto; border-radius: 50%; background: #c59b45; box-shadow: 0 0 0 0 rgba(197,155,69,.34); animation: zhidian-pulse 1.8s infinite; }

.zhidian-panel { position: fixed; z-index: 190; width: min(430px, calc(100vw - 24px)); height: min(680px, calc(100vh - 98px)); display: grid; grid-template-rows: auto auto minmax(0,1fr) auto; overflow: hidden; border: 1px solid #d5e1db; border-radius: 22px; color: #24352f; background: rgba(255,254,250,.985); box-shadow: 0 30px 90px rgba(25,48,40,.24), 0 0 0 1px rgba(255,255,255,.78) inset; backdrop-filter: blur(20px); }
.zhidian-panel.dragging { user-select: none; box-shadow: 0 38px 100px rgba(25,48,40,.3); }
.zhidian-head { min-height: 88px; padding: 20px 16px 12px; position: relative; display: flex; align-items: center; gap: 11px; border-bottom: 1px solid #dfebe5; background: linear-gradient(125deg, #f0faf6 0%, #f9fcfa 58%, #fff8e8 100%); cursor: grab; touch-action: none; }
.zhidian-head:active { cursor: grabbing; }
.drag-handle { width: 54px; height: 5px; position: absolute; top: 8px; left: 50%; border-radius: 999px; background: #c8d6d0; transform: translateX(-50%); }
.zhidian-identity { min-width: 0; display: flex; align-items: center; gap: 9px; }
.zhidian-logo { width: 38px; height: 38px; flex: 0 0 auto; display: grid; place-items: center; border: 1px solid #7cad9b; border-radius: 50% 50% 50% 7px; color: #347762; background: #fbfffd; }
.zhidian-identity div { min-width: 0; display: grid; gap: 2px; }
.zhidian-identity b { font-size: 18px; }
.zhidian-identity small { overflow: hidden; color: #72827c; font-size: 9px; white-space: nowrap; text-overflow: ellipsis; }
.zhidian-mode { margin-left: auto; padding: 5px 8px; display: inline-flex; align-items: center; gap: 5px; border-radius: 999px; color: #3e725f; background: #e1f1ea; font-size: 9px; font-weight: 800; white-space: nowrap; }
.zhidian-mode i { width: 6px; height: 6px; border-radius: 50%; background: #55a184; }
.zhidian-head-actions { display: flex; gap: 5px; }
.zhidian-head-actions button { width: 32px; height: 32px; padding: 0; display: grid; place-items: center; border: 1px solid rgba(62,96,83,.12); border-radius: 9px; color: #3a554b; background: rgba(255,255,255,.82); }
.zhidian-head-actions button:hover { color: #21483b; border-color: #b7cec4; background: #fff; }
.zhidian-context { min-height: 48px; padding: 8px 15px; display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 3px 9px; border-bottom: 1px solid #e6ece8; background: #fbfcfa; }
.zhidian-context span { display: inline-flex; align-items: center; gap: 5px; color: #628075; font-size: 9px; font-weight: 800; }
.zhidian-context b { overflow: hidden; font-size: 11px; white-space: nowrap; text-overflow: ellipsis; }
.zhidian-context small { grid-column: 2; color: #8a9691; font-size: 8px; }
.zhidian-messages { min-height: 0; padding: 17px 15px 22px; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: #abc0b7 transparent; background: radial-gradient(circle at 100% 0, rgba(223,240,233,.45), transparent 32%), #fffefa; }
.zhidian-suggestions { margin-bottom: 16px; padding: 12px; display: grid; gap: 7px; border: 1px solid #e1e8e4; border-radius: 12px; background: rgba(249,251,248,.9); }
.zhidian-suggestions > span { color: #78867f; font-size: 9px; font-weight: 800; }
.zhidian-suggestions button { padding: 8px 9px; display: flex; align-items: center; justify-content: space-between; gap: 8px; border: 0; border-radius: 7px; color: #355248; background: #edf5f1; font-size: 10px; text-align: left; }
.zhidian-suggestions button:hover { color: #21483b; background: #e1efe9; }
.zhidian-message { margin-bottom: 18px; display: grid; justify-items: start; }
.zhidian-message.user { justify-items: end; }
.zhidian-speaker { margin-bottom: 6px; display: flex; align-items: center; gap: 6px; color: #54675f; font-size: 9px; }
.zhidian-speaker > span { width: 24px; height: 24px; display: grid; place-items: center; border-radius: 8px; color: #397b66; background: #e3f0eb; }
.zhidian-speaker em { padding: 3px 6px; border-radius: 999px; color: #6b7658; background: #f2eedf; font-size: 8px; font-style: normal; }
.zhidian-bubble { max-width: 96%; padding: 12px 13px; border: 1px solid #e0e7e3; border-radius: 5px 13px 13px 13px; background: #fff; box-shadow: 0 7px 20px rgba(39,58,50,.035); }
.zhidian-message.user .zhidian-bubble { max-width: 86%; color: #f4faf7; border-color: #315f50; border-radius: 13px 5px 13px 13px; background: linear-gradient(135deg, #315f50, #447b68); font-size: 11px; line-height: 1.65; }
.zhidian-thinking { min-width: 210px; display: flex; align-items: center; gap: 5px; color: #75827c; font-size: 9px; }
.zhidian-thinking i { width: 5px; height: 5px; border-radius: 50%; background: #57927d; animation: zhidian-dot 1.2s infinite ease-in-out; }
.zhidian-thinking i:nth-child(2) { animation-delay: .14s; }.zhidian-thinking i:nth-child(3) { animation-delay: .28s; }.zhidian-thinking span { margin-left: 5px; }
.zhidian-composer { padding: 12px 13px 20px; position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 44px; gap: 8px; border-top: 1px solid #dfe7e2; background: #fbfcfa; }
.zhidian-composer textarea { min-height: 58px; max-height: 110px; padding: 11px 12px 18px; resize: none; border: 1px solid #d6dfda; border-radius: 11px; outline: none; color: #293b34; background: #fff; font: inherit; font-size: 11px; line-height: 1.55; }
.zhidian-composer textarea:focus { border-color: #82aa9a; box-shadow: 0 0 0 3px rgba(79,136,116,.1); }
.zhidian-composer > button { width: 44px; height: 44px; align-self: center; display: grid; place-items: center; border: 0; border-radius: 11px; color: #fff; background: linear-gradient(145deg, #4f8874, #326553); box-shadow: 0 8px 18px rgba(49,95,80,.2); }
.zhidian-composer > button:disabled { opacity: .45; cursor: not-allowed; box-shadow: none; }
.zhidian-composer small { position: absolute; left: 25px; bottom: 8px; color: #98a19d; font-size: 7px; }
.zhidian-panel-enter-active, .zhidian-panel-leave-active { transition: opacity 220ms ease, transform 300ms cubic-bezier(.16,1,.3,1); transform-origin: 92% 96%; }
.zhidian-panel-enter-from, .zhidian-panel-leave-to { opacity: 0; transform: translateY(18px) scale(.9); }
.zhidian-launcher-enter-active, .zhidian-launcher-leave-active { transition: opacity 180ms ease, transform 220ms ease; }
.zhidian-launcher-enter-from, .zhidian-launcher-leave-to { opacity: 0; transform: translateY(10px) scale(.9); }
@keyframes zhidian-pulse { 70% { box-shadow: 0 0 0 9px rgba(197,155,69,0); } 100% { box-shadow: 0 0 0 0 rgba(197,155,69,0); } }
@keyframes zhidian-dot { 0%,80%,100% { opacity: .3; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }

@media (max-width: 680px) {
  .zhidian-launcher { right: 13px; bottom: 15px; min-width: 132px; height: 58px; }
  .launcher-orbit { width: 41px; height: 41px; }
  .zhidian-panel { inset: 76px 8px 8px !important; width: auto; height: auto; border-radius: 17px; }
  .zhidian-mode, .zhidian-identity small { display: none; }
  .zhidian-head { min-height: 70px; padding-top: 15px; }
}
</style>
