<script setup lang="ts">
defineProps<{
  label: string
  /** Live means the tap commits the stake; otherwise the bar is a status readout
   *  for whatever the dealer is doing and must not look pressable. */
  active: boolean
}>()

const emit = defineEmits<{ click: [] }>()
</script>

<template>
  <button
    class="btn press"
    :class="{ live: active }"
    :disabled="!active"
    type="button"
    @click="emit('click')"
  >
    <span class="btn__label">{{ label }}</span>
  </button>
</template>

<style scoped>
.btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  height: 56px;
  border: none;
  border-radius: var(--r-ctl);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  /* Inert state: a flat surface tile that reads as a label, not a control. */
  background: var(--warm-surface);
  color: var(--c-text-2);
  cursor: default;
}

/* Copper, not the catalog blue: this button sits under a warm 3D table and the
   brand accent read as a foreign object dropped on top of the scene. Dark ink
   on copper clears AA by a wide margin; white on it does not. */
.live {
  background: var(--copper);
  color: var(--copper-ink);
  box-shadow: var(--sh-copper);
  cursor: pointer;
}

.live:active {
  background: var(--copper-press);
}

.btn__label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
