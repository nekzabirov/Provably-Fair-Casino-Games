<script setup lang="ts">
import { PhSpeakerHigh, PhSpeakerSlash } from '@phosphor-icons/vue'

defineProps<{ muted: boolean }>()
const emit = defineEmits<{ toggle: [] }>()
</script>

<template>
  <button
    class="mute press"
    type="button"
    :aria-pressed="muted"
    :aria-label="muted ? 'Unmute sound' : 'Mute sound'"
    @click="emit('toggle')"
  >
    <component :is="muted ? PhSpeakerSlash : PhSpeakerHigh" :size="20" weight="bold" />
  </button>
</template>

<style scoped>
.mute {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--tap);
  height: var(--tap);
  padding: 0;
  border-radius: var(--r-chip);
  border: 1px solid var(--c-line);
  background: rgba(21, 30, 42, 0.72);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: var(--c-text-2);
  cursor: pointer;
}

@media (prefers-reduced-transparency: reduce) {
  .mute {
    background: var(--c-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
