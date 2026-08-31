<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ value: number; max?: number; tone?: 'mint' | 'gold' | 'ink' }>(), {
  max: 100,
  tone: 'mint'
})
const ratio = computed(() => Math.max(0, Math.min(1, props.value / Math.max(1, props.max))))
</script>

<template>
  <div class="progress-track" role="progressbar" :aria-valuenow="value" aria-valuemin="0" :aria-valuemax="max">
    <i :class="`progress-${tone}`" :style="{ transform: `scaleX(${ratio})` }" />
  </div>
</template>
