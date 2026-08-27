<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <section class="surface chart-panel" v-reveal>
    <header class="surface-head">
      <div>
        <h2>{{ title }}</h2>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
      <slot name="action" />
    </header>
    <div class="surface-body">
      <slot />
    </div>
  </section>
</template>
