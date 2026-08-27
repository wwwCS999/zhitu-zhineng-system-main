<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  foot?: string
  tone?: 'mint' | 'cream' | 'plain'
}>()
</script>

<template>
  <article class="metric-card" :class="`tone-${tone || 'plain'}`" v-reveal>
    <div class="metric-label">
      <span>{{ label }}</span>
      <slot name="icon" />
    </div>
    <strong class="metric-value">{{ value }}</strong>
    <p v-if="foot">{{ foot }}</p>
  </article>
</template>
