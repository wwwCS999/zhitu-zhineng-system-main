<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ score: number; label?: string }>()
const tone = computed(() => props.score >= 0.78 ? 'good' : props.score >= 0.55 ? 'warn' : 'risk')
</script>

<template>
  <span class="status-badge" :class="tone">
    {{ label || '可信度' }} {{ Math.round((score || 0) * 100) }}%
  </span>
</template>
