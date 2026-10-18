<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import { PhMinus, PhPlus } from '@phosphor-icons/vue'
import PrimaryButton from './PrimaryButton.vue'
import TonIcon from './TonIcon.vue'
import type { Risk } from '../tables'

defineProps<{
  bet: string
  presets: number[]
  rows: number
  risk: Risk
  /** Bet stepper/presets lock (disconnected only — bets stay live mid-flight). */
  betDisabled: boolean
  /** Rows/risk lock — changing them rebuilds the board, so they wait for the air to clear. */
  paramsDisabled: boolean
  primaryDisabled: boolean
}>()

const emit = defineEmits<{
  betChange: [delta: number]
  betSet: [value: number]
  rowsChange: [delta: number]
  riskSet: [risk: Risk]
  primaryDown: []
  primaryUp: []
}>()

// The selector stays neutral on purpose. Colouring the chosen segment by
// volatility (green / gold / red) collides twice: gold already means "top
// prize" in the ladder sitting directly above it, and red already means "you
// lost". Changing risk visibly rewrites that ladder, so the volatility is
// already shown, in the one place that states it exactly.
const RISKS: { value: Risk; label: string }[] = [
  { value: 'LOW', label: 'Low' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'HIGH', label: 'High' },
]

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
    <div class="row">
      <div class="stake" :class="{ locked: betDisabled }">
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

      <div class="stake rowsctl" :class="{ locked: paramsDisabled }">
        <button
          class="step press"
          type="button"
          aria-label="Fewer rows"
          :disabled="paramsDisabled"
          @click="emit('rowsChange', -1)"
        >
          <PhMinus :size="18" weight="bold" />
        </button>

        <div class="amount">
          <span class="amount__l">rows</span>
          <span class="amount__row">
            <span class="amount__v amount__v--sm tnum">{{ rows }}</span>
          </span>
        </div>

        <button
          class="step press"
          type="button"
          aria-label="More rows"
          :disabled="paramsDisabled"
          @click="emit('rowsChange', 1)"
        >
          <PhPlus :size="18" weight="bold" />
        </button>
      </div>
    </div>

    <div class="presets">
      <button
        v-for="p in presets"
        :key="p"
        class="preset press tnum"
        type="button"
        :disabled="betDisabled"
        @click="emit('betSet', p)"
      >
        {{ p }}
      </button>
    </div>

    <div class="risk" :class="{ locked: paramsDisabled }" role="group" aria-label="Risk">
      <button
        v-for="r in RISKS"
        :key="r.value"
        class="seg press"
        type="button"
        :class="{ on: r.value === risk }"
        :disabled="paramsDisabled"
        :aria-pressed="r.value === risk"
        @click="emit('riskSet', r.value)"
      >
        {{ r.label }}
      </button>
    </div>

    <PrimaryButton
      :disabled="primaryDisabled"
      @press="emit('primaryDown')"
      @release="emit('primaryUp')"
    >
      <span class="drop">Drop<span class="drop__dot">•</span><span class="tnum">{{ bet }}</span>&nbsp;<TonIcon :size="15" /></span>
    </PrimaryButton>
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

.row {
  display: flex;
  align-items: stretch;
  gap: 9px;
}

/* ── stake and rows steppers ──────────────────────────────────────────────── */
.stake {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px;
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  transition: opacity var(--d-pop) var(--e-out);
}

.rowsctl {
  flex: 0 0 auto;
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

.amount__v--sm {
  font-size: 19px;
  min-width: 26px;
  text-align: center;
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
  height: 44px;
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

/* ── risk selector ────────────────────────────────────────────────────────── */
/* Unselected segments are bare text on the track; the chosen one is a filled
   chip in the brand accent, like every other "this is the setting in force"
   state in the catalog. */
.risk {
  display: flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  transition: opacity var(--d-pop) var(--e-out);
}

.risk.locked {
  opacity: 0.45;
}

.seg {
  flex: 1 1 0;
  min-width: 0;
  height: 46px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--c-text-3);
  font-family: inherit;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
  cursor: pointer;
  touch-action: manipulation;
}

.seg:disabled {
  cursor: default;
}

.seg.on {
  background: var(--c-brand);
  color: var(--pay-ink);
  font-weight: 700;
}

/* ── primary ──────────────────────────────────────────────────────────────── */
.drop {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.drop__dot {
  margin: 0 9px;
  opacity: 0.5;
}
</style>
