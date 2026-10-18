<script setup lang="ts">
defineProps<{
  label: string
  /** 'bet' = the gold call to arms (join / reinforce), 'locked' = the round is
   *  resolving and no money can move. Colour carries that, not the label. */
  variant: 'bet' | 'locked'
  disabled?: boolean
  /** Action sent, awaiting the engine's confirming frame - show a spinner. */
  loading?: boolean
}>()

const emit = defineEmits<{ click: [] }>()
</script>

<template>
  <button
    class="btn press"
    :class="[variant, { loading }]"
    :disabled="disabled || loading"
    type="button"
    @click="emit('click')"
  >
    <span v-if="loading" class="btn__spinner" aria-label="Sending" />
    <span v-else class="btn__label">{{ label }}</span>
  </button>
</template>

<style scoped>
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
  font-variant-numeric: tabular-nums;
}

.btn__label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* Dark ink on gold: white on this saturation sits near 2:1 and fails AA, the
   dark ink clears 8:1. */
.bet {
  background: var(--arena-gold);
  color: var(--arena-ink);
  box-shadow: 0 6px 18px var(--arena-glow);
}

.bet:not(:disabled):active {
  background: var(--arena-gold-press);
}

.locked {
  background: var(--c-surface-2);
  color: var(--c-text-3);
}

.btn:disabled {
  background: var(--c-surface-2);
  color: var(--c-text-3);
  box-shadow: none;
  cursor: default;
}

/* Busy reads as active, not greyed-out, so a sent action never looks dead. */
.btn.loading {
  opacity: 0.9;
  cursor: progress;
}

.btn__spinner {
  display: inline-block;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2.5px solid rgba(42, 28, 0, 0.25);
  border-top-color: var(--arena-ink);
  animation: btn-spin 0.7s linear infinite;
}

.locked .btn__spinner {
  border-color: var(--c-line-2);
  border-top-color: var(--c-text-2);
}

@keyframes btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
