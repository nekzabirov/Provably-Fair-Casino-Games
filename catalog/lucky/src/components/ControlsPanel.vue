<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import { PhMinus, PhPlus } from '@phosphor-icons/vue'
import PayoutStrip from './PayoutStrip.vue'
import PrimaryButton from './PrimaryButton.vue'
import TonIcon from './TonIcon.vue'

defineProps<{
  bet: string
  /** Pre-formatted payout on a correct pick (stake x2). */
  toWin: string
  /** Show the bet stepper (waiting to start); otherwise show the 1/2/3 cup buttons. */
  showBet: boolean
  betDisabled: boolean
  /** 1/2/3 cup buttons live (waiting for a pick). */
  slotsEnabled: boolean
  primaryLabel: string
  /** The wide bar acts as a button (start / play again). */
  primaryActive: boolean
}>()

const emit = defineEmits<{
  betChange: [delta: number]
  slot: [index: number]
  primary: []
}>()

// Press-and-hold the bet steppers to keep changing the amount; a single tap
// still fires once. Repeats accelerate slightly after the initial hold delay.
let holdDelay = 0
let holdRepeat = 0

function startHold(delta: number) {
  stopHold()
  emit('betChange', delta)
  holdDelay = window.setTimeout(() => {
    holdRepeat = window.setInterval(() => emit('betChange', delta), 70)
  }, 360)
}

function stopHold() {
  window.clearTimeout(holdDelay)
  window.clearInterval(holdRepeat)
  holdDelay = 0
  holdRepeat = 0
}

onBeforeUnmount(stopHold)
</script>

<template>
  <div class="panel">
    <PayoutStrip :to-win="toWin" />

    <!-- The stake row and the cup picker swap in the same slot: the stake can no
         longer be changed once the dealer starts, so showing a locked stepper
         next to a live picker would only add noise. -->
    <div v-if="showBet" class="stake" :class="{ locked: betDisabled }">
      <button
        class="step press"
        type="button"
        aria-label="Decrease bet"
        :disabled="betDisabled"
        @pointerdown.prevent="startHold(-1)"
        @pointerup="stopHold"
        @pointerleave="stopHold"
        @pointercancel="stopHold"
      >
        <PhMinus :size="20" weight="bold" />
      </button>

      <div class="amount">
        <span class="amount__l">bet</span>
        <span class="amount__row">
          <span class="amount__v tnum">{{ bet }}</span>
          <TonIcon :size="13" />
        </span>
      </div>

      <button
        class="step press"
        type="button"
        aria-label="Increase bet"
        :disabled="betDisabled"
        @pointerdown.prevent="startHold(1)"
        @pointerup="stopHold"
        @pointerleave="stopHold"
        @pointercancel="stopHold"
      >
        <PhPlus :size="20" weight="bold" />
      </button>
    </div>

    <!-- Cups are picked on the 3D table itself; these mirror that reach-free, so
         a player who cannot hit a cup accurately still has a full-size target. -->
    <div v-else class="slots">
      <button
        v-for="n in 3"
        :key="n"
        class="slot press tnum"
        type="button"
        :disabled="!slotsEnabled"
        :aria-label="`Pick cup ${n}`"
        @click="emit('slot', n - 1)"
      >
        {{ n }}
      </button>
    </div>

    <PrimaryButton :label="primaryLabel" :active="primaryActive" @click="emit('primary')" />
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 12px var(--pad-x);
  padding-bottom: calc(var(--safe-bottom) + 14px);
  width: 100%;
  /* Opaque, not translucent: this slab sits under a lit render and is the one
     place where every reading must be guaranteed legible. */
  background: var(--warm-bg-2);
  border-top: 1px solid var(--c-line-2);
}

/* ── stake row ────────────────────────────────────────────────────────────── */
.stake {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px;
  border-radius: var(--r-ctl);
  background: var(--warm-surface);
  border: 1px solid var(--c-line);
  transition: opacity var(--d-pop) var(--e-out);
}

.stake.locked {
  opacity: 0.45;
}

.step {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--tap);
  height: var(--tap);
  border: none;
  border-radius: 10px;
  background: var(--copper-soft);
  color: var(--copper);
  cursor: pointer;
  touch-action: manipulation;
}

.step:disabled {
  cursor: default;
}

.amount {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

.amount__l {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--c-text-3);
  line-height: 1;
}

.amount__row {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.amount__v {
  font-size: 21px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── cup picker ───────────────────────────────────────────────────────────── */
.slots {
  display: flex;
  gap: 8px;
}

.slot {
  flex: 1 1 0;
  /* Sized so the picker occupies exactly the height the stake row vacates: the
     3D table must not shift when the panel swaps contents mid-round. */
  height: 58px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--r-ctl);
  background: var(--warm-surface);
  color: var(--copper);
  font-size: 22px;
  font-weight: 700;
  cursor: pointer;
  touch-action: manipulation;
}

.slot:disabled {
  opacity: 0.4;
  color: var(--c-text-3);
  cursor: default;
}

.slot:not(:disabled):active {
  background: var(--warm-surface-2);
}
</style>
