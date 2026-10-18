<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import { PhArrowCounterClockwise, PhBomb, PhMinus, PhPlus } from '@phosphor-icons/vue'
import PrimaryButton from './PrimaryButton.vue'
import TonIcon from './TonIcon.vue'

defineProps<{
  bet: string
  presets: number[]
  mineCount: number | string
  primaryLabel: string
  primaryVariant: 'bet' | 'cashout'
  /** Render the TON icon after the primary label (cash-out payout). */
  primaryShowCurrency: boolean
  /** Bet / mines locked (during an active round). */
  inputsDisabled: boolean
  primaryDisabled: boolean
  /** Show the "repeat last bet" button (idle state only). */
  showRepeat: boolean
}>()

const emit = defineEmits<{
  betChange: [delta: number]
  betSet: [value: number]
  openMines: []
  primary: []
  repeat: []
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
    <!-- Setup row: everything that defines the next round. Locked, and visibly
         so, while a round is running — the stake can no longer be changed. -->
    <div class="setup" :class="{ locked: inputsDisabled }">
      <div class="stake">
        <button
          class="step press"
          type="button"
          aria-label="Decrease bet"
          :disabled="inputsDisabled"
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
          :disabled="inputsDisabled"
          @pointerdown.prevent="startHold(1)"
          @pointerup="stopHold"
          @pointerleave="stopHold"
          @pointercancel="stopHold"
        >
          <PhPlus :size="20" weight="bold" />
        </button>
      </div>

      <button
        class="mines press"
        type="button"
        :disabled="inputsDisabled"
        :aria-label="`Mines: ${mineCount}`"
        @click="emit('openMines')"
      >
        <PhBomb :size="17" weight="fill" />
        <span class="mines__n tnum">{{ mineCount }}</span>
      </button>
    </div>

    <div class="presets">
      <button
        v-for="p in presets"
        :key="p"
        class="preset press tnum"
        type="button"
        :disabled="inputsDisabled"
        @click="emit('betSet', p)"
      >
        {{ p }}
      </button>
      <button
        v-if="showRepeat"
        class="preset press repeat"
        type="button"
        :disabled="primaryDisabled"
        aria-label="Repeat last bet"
        @click="emit('repeat')"
      >
        <PhArrowCounterClockwise :size="17" weight="bold" />
      </button>
    </div>

    <PrimaryButton
      :label="primaryLabel"
      :show-currency="primaryShowCurrency"
      :variant="primaryVariant"
      :disabled="primaryDisabled"
      @click="emit('primary')"
    />
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
  background: var(--c-bg-2);
  border-top: 1px solid var(--c-line);
}

/* ── setup row ────────────────────────────────────────────────────────────── */
.setup {
  display: flex;
  align-items: stretch;
  gap: 9px;
  transition: opacity var(--d-pop) var(--e-out);
}

.setup.locked {
  opacity: 0.45;
}

.stake {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px;
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
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
  background: var(--c-brand-soft);
  color: var(--c-brand);
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

.mines {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 74px;
  padding: 0 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  color: var(--c-lose);
  cursor: pointer;
  touch-action: manipulation;
}

.mines__n {
  font-size: 17px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1;
}

.mines:disabled {
  cursor: default;
}

/* ── quick amounts ────────────────────────────────────────────────────────── */
.presets {
  display: flex;
  gap: 6px;
}

.preset {
  flex: 1 1 0;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 40px;
  padding: 0 4px;
  border: 1px solid var(--c-line);
  border-radius: 10px;
  background: var(--c-surface);
  color: var(--c-text-2);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  touch-action: manipulation;
}

.preset:not(:disabled):active {
  background: var(--c-surface-2);
}

.preset:disabled {
  opacity: 0.45;
  cursor: default;
}

.repeat {
  flex: 0 0 auto;
  width: 44px;
  color: var(--c-brand);
}
</style>
