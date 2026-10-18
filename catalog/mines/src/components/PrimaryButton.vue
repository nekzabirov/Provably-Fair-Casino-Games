<script setup lang="ts">
import TonIcon from './TonIcon.vue'

defineProps<{
  label: string
  /** Colour carries the meaning of the action: 'bet' commits money, 'cashout'
   *  takes it back. The player should never have to read the label to tell. */
  variant: 'bet' | 'cashout'
  /** Render the TON icon after the label (e.g. the cash-out payout amount). */
  showCurrency?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{ click: [] }>()
</script>

<template>
  <button
    class="btn press"
    :class="variant"
    :disabled="disabled"
    type="button"
    @click="emit('click')"
  >
    <span class="btn__label">{{ label }}</span>
    <TonIcon v-if="showCurrency" :size="17" />
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
  cursor: pointer;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: #04121C;
  /* Money is the label here; it must not jitter as the payout ticks up. */
  font-variant-numeric: tabular-nums;
}

/* The label is dark-on-bright rather than white-on-bright: at these
   saturations white text sits at ~2.6:1 and fails AA, dark ink clears 8:1. */
.bet {
  background: var(--c-brand);
  box-shadow: var(--sh-cta);
}

.cashout {
  background: var(--c-win);
  box-shadow: var(--sh-win);
}

.bet:not(:disabled):active {
  background: var(--c-brand-press);
}

.cashout:not(:disabled):active {
  background: var(--c-win-press);
}

.btn:disabled {
  background: var(--c-surface-2);
  color: var(--c-text-3);
  box-shadow: none;
  cursor: default;
}

.btn__label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
