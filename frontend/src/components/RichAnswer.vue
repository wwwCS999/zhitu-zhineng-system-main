<script setup lang="ts">
import { computed } from 'vue'

type InlinePart = {
  text: string
  kind: 'text' | 'strong' | 'code' | 'evidence'
}

type AnswerBlock = {
  type: 'heading' | 'paragraph' | 'list' | 'callout'
  tone?: 'normal' | 'success' | 'warning'
  parts?: InlinePart[]
  items?: InlinePart[][]
}

const props = withDefaults(defineProps<{ content?: string; compact?: boolean }>(), {
  content: '',
  compact: false
})

function inlineParts(source: string): InlinePart[] {
  const clean = source.replace(/\*+/g, '').trim()
  const pattern = /(\[证据\s*\d+\]|`[^`]+`|【[^】]{1,22}】|“[^”]{1,36}”)/g
  const parts: InlinePart[] = []
  let cursor = 0
  let match: RegExpExecArray | null

  while ((match = pattern.exec(clean)) !== null) {
    if (match.index > cursor) parts.push({ text: clean.slice(cursor, match.index), kind: 'text' })
    const value = match[0]
    if (/^\[证据\s*\d+\]$/.test(value)) {
      parts.push({ text: value.replace(/[\[\]]/g, ''), kind: 'evidence' })
    } else if (value.startsWith('`')) {
      parts.push({ text: value.slice(1, -1), kind: 'code' })
    } else {
      parts.push({ text: value, kind: 'strong' })
    }
    cursor = match.index + value.length
  }
  if (cursor < clean.length) parts.push({ text: clean.slice(cursor), kind: 'text' })
  return parts.length ? parts : [{ text: clean, kind: 'text' }]
}

function parseAnswer(source: string): AnswerBlock[] {
  const lines = String(source || '')
    .replace(/\r/g, '')
    .replace(/\*\*([^*]+)\*\*/g, '【$1】')
    .split('\n')
  const blocks: AnswerBlock[] = []
  let paragraph: string[] = []
  let list: InlinePart[][] = []

  const flushParagraph = () => {
    const text = paragraph.join(' ').trim()
    paragraph = []
    if (!text) return
    const isCallout = /^(结论|简言之|核心判断|建议|注意|预测结论)[：:]/.test(text) || /^[✅💡]/.test(text)
    const isWarning = /^(注意|风险|限制|数据不足)[：:]|^[⚠]/.test(text)
    blocks.push({
      type: isCallout || isWarning ? 'callout' : 'paragraph',
      tone: isWarning ? 'warning' : isCallout ? 'success' : 'normal',
      parts: inlineParts(text.replace(/^[✅💡⚠️\s]+/, ''))
    })
  }
  const flushList = () => {
    if (!list.length) return
    blocks.push({ type: 'list', items: list })
    list = []
  }

  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line) {
      flushParagraph()
      flushList()
      continue
    }

    const heading = line.match(/^#{1,6}\s*(.+)$/) || line.match(/^【([^】]{2,42})】[：:]?$/)
    if (heading) {
      flushParagraph()
      flushList()
      blocks.push({ type: 'heading', parts: inlineParts(heading[1]) })
      continue
    }

    const item = line.match(/^(?:[-+•*]|\d+[.、）)]|[一二三四五六七八九十]+[、.])\s*(.+)$/)
    if (item) {
      flushParagraph()
      list.push(inlineParts(item[1]))
      continue
    }

    flushList()
    paragraph.push(line)
  }
  flushParagraph()
  flushList()
  return blocks
}

const blocks = computed(() => parseAnswer(props.content))
</script>

<template>
  <div class="rich-answer" :class="{ compact }">
    <template v-for="(block, blockIndex) in blocks" :key="blockIndex">
      <h3 v-if="block.type === 'heading'" class="answer-heading">
        <span class="heading-mark" />
        <template v-for="(part, partIndex) in block.parts" :key="partIndex">{{ part.text }}</template>
      </h3>

      <p v-else-if="block.type === 'paragraph'" class="answer-paragraph">
        <template v-for="(part, partIndex) in block.parts" :key="partIndex">
          <strong v-if="part.kind === 'strong'">{{ part.text }}</strong>
          <code v-else-if="part.kind === 'code'">{{ part.text }}</code>
          <span v-else-if="part.kind === 'evidence'" class="evidence-ref">{{ part.text }}</span>
          <span v-else>{{ part.text }}</span>
        </template>
      </p>

      <div v-else-if="block.type === 'callout'" class="answer-callout" :class="block.tone">
        <span class="callout-icon">{{ block.tone === 'warning' ? '!' : '✓' }}</span>
        <p>
          <template v-for="(part, partIndex) in block.parts" :key="partIndex">
            <strong v-if="part.kind === 'strong'">{{ part.text }}</strong>
            <code v-else-if="part.kind === 'code'">{{ part.text }}</code>
            <span v-else-if="part.kind === 'evidence'" class="evidence-ref">{{ part.text }}</span>
            <span v-else>{{ part.text }}</span>
          </template>
        </p>
      </div>

      <ol v-else class="answer-list">
        <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
          <span class="list-index">{{ String(itemIndex + 1).padStart(2, '0') }}</span>
          <p>
            <template v-for="(part, partIndex) in item" :key="partIndex">
              <strong v-if="part.kind === 'strong'">{{ part.text }}</strong>
              <code v-else-if="part.kind === 'code'">{{ part.text }}</code>
              <span v-else-if="part.kind === 'evidence'" class="evidence-ref">{{ part.text }}</span>
              <span v-else>{{ part.text }}</span>
            </template>
          </p>
        </li>
      </ol>
    </template>
  </div>
</template>

<style scoped>
.rich-answer { display: grid; gap: 12px; color: #304039; font-size: 13px; line-height: 1.82; }
.answer-paragraph { margin: 0; }
.answer-paragraph strong, .answer-list strong { color: #193e32; font-weight: 850; }
.answer-heading { margin: 7px 0 0; display: flex; align-items: center; gap: 9px; color: #20352e; font-size: 14px; letter-spacing: -.01em; }
.heading-mark { width: 4px; height: 16px; border-radius: 999px; background: linear-gradient(180deg, #4f8874, #d1a33d); box-shadow: 0 0 0 4px rgba(79, 136, 116, .08); }
.answer-list { margin: 0; padding: 0; display: grid; gap: 8px; list-style: none; }
.answer-list li { padding: 9px 11px; display: grid; grid-template-columns: 30px minmax(0, 1fr); gap: 9px; align-items: start; border: 1px solid #e0e8e3; border-radius: 9px; background: rgba(248, 251, 248, .82); }
.answer-list p { margin: 0; }
.list-index { width: 26px; height: 26px; display: grid; place-items: center; border-radius: 7px; color: #3e725f; background: #e3f0eb; font-family: Consolas, monospace; font-size: 9px; font-weight: 800; }
.answer-callout { padding: 12px 13px; display: grid; grid-template-columns: 25px minmax(0, 1fr); gap: 9px; border: 1px solid #cfe2da; border-radius: 10px; background: linear-gradient(105deg, #edf7f2, #fbfdfb); }
.answer-callout.warning { border-color: #ead9ad; background: linear-gradient(105deg, #fff8e8, #fffdf8); }
.answer-callout p { margin: 0; }
.callout-icon { width: 24px; height: 24px; display: grid; place-items: center; border-radius: 50%; color: #fff; background: #4f8874; font-size: 11px; font-weight: 900; }
.warning .callout-icon { background: #c49332; }
.evidence-ref { margin: 0 2px; padding: 2px 6px; display: inline-flex; border-radius: 999px; color: #3f6f60; background: #e4f0eb; font-size: .78em; font-weight: 850; white-space: nowrap; vertical-align: .08em; }
code { padding: 2px 5px; border-radius: 5px; color: #52693f; background: #eef1e8; font-family: "SF Mono", Consolas, monospace; font-size: .88em; }
.compact { gap: 9px; font-size: 12px; line-height: 1.72; }
.compact .answer-heading { font-size: 12px; }
.compact .answer-list li { padding: 8px 9px; grid-template-columns: 25px minmax(0, 1fr); }
.compact .list-index { width: 22px; height: 22px; }
.compact .answer-callout { padding: 10px; }
</style>
