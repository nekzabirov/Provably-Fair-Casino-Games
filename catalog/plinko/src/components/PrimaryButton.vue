<script setup lang="ts">
defineProps<{
  label?: string
  disabled?: boolean
}>()

// press/release rather than click: holding the button auto-drops, so the screen
// needs both edges of the gesture.
const emit = defineEmits<{ press: []; release: [] }>()
</script>

<template>
  <button
    class="btn press"
    :disabled="disabled"
    type="button"
    @pointerdown="emit('press')"
    @pointerup="emit('release')"
    @pointercancel="emit('release')"
    @pointerleave="emit('release')"
    @contextmenu.prevent
  >
    <slot>{{ label }}</slot>
  </button>
</template>

<style scoped>
/* Dark ink on the brand fill: white sits at ~2.6:1 on this blue and fails AA,
   dark ink clears 8:1. */
.btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 56px;
  border: none;
  border-radius: var(--r-ctl);
  cursor: pointer;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  font-family: inherit;
  background: var(--c-brand);
  box-shadow: var(--sh-cta);
  color: #04121C;
  touch-action: none;
  font-variant-numeric: tabular-nums;
}

.btn:not(:disabled):active {
  background: var(--c-brand-press);
}

.btn:disabled {
  background: var(--c-surface-2);
  color: var(--c-text-3);
  box-shadow: none;
  cursor: default;
}
</style>
