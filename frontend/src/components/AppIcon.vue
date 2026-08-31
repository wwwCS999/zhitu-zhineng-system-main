<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })
</script>

<template>
  <svg
    class="app-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2.15"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'home'">
      <path d="M3 11.5 12 4l9 7.5"/><path d="M5.5 10.5V20h13v-9.5"/><path d="M9.5 20v-6h5v6"/>
    </template>
    <template v-else-if="name === 'database'">
      <ellipse cx="12" cy="5.5" rx="7.5" ry="3"/><path d="M4.5 5.5v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-6"/><path d="M4.5 11.5v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-6"/>
    </template>
    <template v-else-if="name === 'scan'">
      <path d="M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4"/><path d="M7 9h10M7 12h10M7 15h7"/>
    </template>
    <template v-else-if="name === 'spark'">
      <path d="m12 3 1.7 4.3L18 9l-4.3 1.7L12 15l-1.7-4.3L6 9l4.3-1.7L12 3Z"/><path d="m19 15 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z"/>
    </template>
    <template v-else-if="name === 'pulse'">
      <path d="M3 12h4l2.2-5 4.2 10 2.1-5H21"/>
    </template>
    <template v-else-if="name === 'target'">
      <circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>
    </template>
    <template v-else-if="name === 'briefcase'">
      <path d="M9 6V4h6v2"/><path d="M4 7h16v12H4V7Z"/><path d="M4 12h16"/><path d="M10 12v2h4v-2"/>
    </template>
    <template v-else-if="name === 'network'">
      <circle cx="6" cy="6" r="2.5"/><circle cx="18" cy="7" r="2.5"/><circle cx="12" cy="18" r="2.5"/><path d="m8.2 7.2 7.5-.1M7.5 8.2l3.2 7.5M16.7 9l-3.4 6.7"/>
    </template>
    <template v-else-if="name === 'match'">
      <circle cx="9" cy="9" r="4"/><path d="M3.5 20c.8-4 3-6 5.5-6s4.7 2 5.5 6"/><path d="m16 12 2 2 3.5-4"/>
    </template>
    <template v-else-if="name === 'route'">
      <circle cx="5" cy="18" r="2"/><circle cx="19" cy="6" r="2"/><path d="M7 18h3c4 0 4-5 0-5H8c-4 0-4-5 0-5h9"/>
    </template>
    <template v-else-if="name === 'audit'">
      <path d="M12 3 5 6v5c0 4.8 2.9 8.1 7 10 4.1-1.9 7-5.2 7-10V6l-7-3Z"/><path d="m8.5 12 2.2 2.2 4.8-5"/>
    </template>
    <template v-else-if="name === 'chat'">
      <path d="M4 5h16v11H9l-5 4V5Z"/><path d="M8 9h8M8 12h5"/>
    </template>
    <template v-else-if="name === 'refresh'">
      <path d="M20 6v5h-5"/><path d="M18.5 9a7 7 0 1 0 .5 6"/>
    </template>
    <template v-else-if="name === 'play'">
      <path d="m8 5 11 7-11 7V5Z"/>
    </template>
    <template v-else-if="name === 'pause'">
      <path d="M8 5v14M16 5v14"/>
    </template>
    <template v-else-if="name === 'bell'">
      <path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 7h18s-3 0-3-7"/><path d="M10 20h4"/>
    </template>
    <template v-else-if="name === 'menu'">
      <path d="M4 7h16M4 12h16M4 17h16"/>
    </template>
    <template v-else-if="name === 'close'">
      <path d="m6 6 12 12M18 6 6 18"/>
    </template>
    <template v-else-if="name === 'arrow'">
      <path d="M5 12h14M14 7l5 5-5 5"/>
    </template>
    <template v-else-if="name === 'upload'">
      <path d="M12 16V4M7 9l5-5 5 5"/><path d="M5 15v5h14v-5"/>
    </template>
    <template v-else-if="name === 'file'">
      <path d="M6 3h8l4 4v14H6V3Z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>
    </template>
    <template v-else-if="name === 'image'">
      <path d="M4 5h16v14H4V5Z"/><circle cx="8.5" cy="9" r="1.5"/><path d="m6 17 4.2-4.2 2.8 2.8 2-2L19 17"/>
    </template>
    <template v-else-if="name === 'link'">
      <path d="m10 13 4-4"/><path d="M7.5 15.5 5 18a3.5 3.5 0 0 1-5-5l3-3a3.5 3.5 0 0 1 5 0" transform="translate(3)"/><path d="m13.5 8.5 2.5-2.5a3.5 3.5 0 1 1 5 5l-3 3a3.5 3.5 0 0 1-5 0" transform="translate(-3)"/>
    </template>
    <template v-else-if="name === 'focus'">
      <path d="M8 4H4v4M16 4h4v4M20 16v4h-4M8 20H4v-4"/><circle cx="12" cy="12" r="3"/>
    </template>
    <template v-else-if="name === 'check'">
      <path d="m5 12 4 4L19 6"/>
    </template>
    <template v-else-if="name === 'send'">
      <path d="m3 3 18 9-18 9 4-9-4-9Z"/><path d="M7 12h14"/>
    </template>
    <template v-else-if="name === 'book'">
      <path d="M4 5.5A3.5 3.5 0 0 1 7.5 2H11v17H7.5A3.5 3.5 0 0 0 4 22V5.5ZM20 5.5A3.5 3.5 0 0 0 16.5 2H13v17h3.5A3.5 3.5 0 0 1 20 22V5.5Z"/>
    </template>
    <template v-else-if="name === 'user'">
      <circle cx="12" cy="8" r="4"/><path d="M4.5 21c.8-5 3.4-7 7.5-7s6.7 2 7.5 7"/>
    </template>
    <template v-else-if="name === 'brain'">
      <path d="M9.5 4.5A3.5 3.5 0 0 0 6 8a3 3 0 0 0-1 5.8A3.5 3.5 0 0 0 9.5 19V4.5ZM14.5 4.5A3.5 3.5 0 0 1 18 8a3 3 0 0 1 1 5.8A3.5 3.5 0 0 1 14.5 19V4.5Z"/><path d="M9.5 9H7M9.5 14H7.5M14.5 9H17M14.5 14H17"/>
    </template>
    <template v-else-if="name === 'edit'">
      <path d="M4 20h4l11-11-4-4L4 16v4Z"/><path d="m13.5 6.5 4 4"/>
    </template>
    <template v-else-if="name === 'trash'">
      <path d="M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13"/><path d="M10 11v5M14 11v5"/>
    </template>
    <template v-else-if="name === 'plus'">
      <path d="M12 5v14M5 12h14"/>
    </template>
    <template v-else-if="name === 'chevron'">
      <path d="m9 6 6 6-6 6"/>
    </template>
    <template v-else-if="name === 'scroll'">
      <path d="M12 4v16M8.5 7.5 12 4l3.5 3.5M8.5 16.5 12 20l3.5-3.5"/>
    </template>
    <template v-else>
      <circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>
    </template>
  </svg>
</template>
