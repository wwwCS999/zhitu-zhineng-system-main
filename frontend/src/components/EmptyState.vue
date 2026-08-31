<script setup lang="ts">
import AppIcon from './AppIcon.vue'
defineProps<{ title?: string; description?: string }>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon"><AppIcon name="file" :size="24" /></div>
    <h3>{{ title || '暂无数据' }}</h3>
    <p>{{ description || '运行相应智能体后，结果会显示在这里。' }}</p>
    <slot />
  </div>
</template>
