<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { PhMinus, PhPlus, PhX } from '@phosphor-icons/vue'
import PrimaryButton from './PrimaryButton.vue'
import TonIcon from './TonIcon.vue'

const props = defineProps<{
  /** Next-bet amount (display, major units). */
  bet: string
  presets: number[]
  /** Pre-formatted stats. */
  pot: string
  stake: string
  chance: string
  /** Win chance as 0..100, for the share meter. */
  chancePct: number
  primaryLabel: string
  primaryVariant: 'bet' | 'locked'
  primaryDisabled: boolean
  /** An enter-battle / cancel is in flight, awaiting the engine's confirmation. */
  busy: boolean
  /** Bet stepper / presets disabled (locked round or disconnected). */
  inputsDisabled: boolean
  /** Show the cancel button (you have a stake and the round isn't locked). */
  canCancel: boolean
}>()

const emit = defineEmits<{
  betChange: [delta: number]
  betSet: [value: number]
  primary: []
  cancel: []
}>()

// The meter never renders as literally zero-width once a stake exists: a sliver
// still has to be findable, otherwise "in with a small stake" looks like "out".
const meterWidth = computed(() => {
  const p = props.chancePct
  if (!Number.isFinite(p) || p <= 0) return '0%'
  return `${Math.min(100, Math.max(2.5, p))}%`
})

// Bump the war chest whenever the pot grows.
const potBump = ref(false)
let potTimer = 0
watch(
  () => props.pot,
  () => {
    potBump.value = false
    window.clearTimeout(potTimer)
    requestAnimationFrame(() => {
      potBump.value = true
      potTimer = window.setTimeout(() => (potBump.value = false), 420)
    })
  },
)

// Press-and-hold the steppers to keep changing the amount.
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

onBeforeUnmount(() => {
  stopHold()
  window.clearTimeout(potTimer)
})
</script>

<template>
  <div class="panel">
    <!-- The decision readout: what is on the table, what you put in, and what
         that buys you. The share meter turns the percentage into a length so
         the effect of topping up is visible before the number is read. -->
    <div class="readout">
      <div class="cells">
        <div class="cell">
          <span class="cell__l">War chest</span>
          <span class="cell__v gold tnum" :class="{ bump: potBump }">
            {{ pot }}<TonIcon :size="15" />
          </span>
        </div>
        <div class="cell">
          <span class="cell__l">Your bet</span>
          <span class="cell__v tnum">{{ stake }}<TonIcon :size="14" /></span>
        </div>
        <div class="cell chance">
          <span class="cell__l">Win chance</span>
          <span class="cell__v gold tnum">{{ chance }}</span>
        </div>
      </div>

      <div class="meter">
        <div class="meter__track">
          <div class="meter__fill" :style="{ width: meterWidth }" />
        </div>
        <span class="meter__cap">Your share of the pot</span>
      </div>
    </div>

    <div class="stepper" :class="{ disabled: inputsDisabled }">
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
    </div>

    <div class="actions">
      <button
        v-if="canCancel"
        class="cancel press"
        :class="{ busy }"
        type="button"
        aria-label="Cancel your bet"
        :disabled="busy"
        @click="emit('cancel')"
      >
        <span v-if="busy" class="cancel__spinner" aria-label="Cancelling" />
        <PhX v-else :size="20" weight="bold" />
      </button>
      <div class="primary">
        <PrimaryButton
          :label="primaryLabel"
          :variant="primaryVariant"
          :disabled="primaryDisabled"
          :loading="busy"
          @click="emit('primary')"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 10px var(--pad-x);
  padding-bottom: calc(var(--safe-bottom) + 14px);
  width: 100%;
  background: var(--c-bg-2);
  border-top: 1px solid var(--c-line);
}

/* ── decision readout ─────────────────────────────────────────────────────── */
.readout {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 10px 12px;
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  box-shadow: var(--sh-1);
}

.cells {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
}

.cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  min-width: 0;
  padding: 0 6px;
}

.cell + .cell {
  border-left: 1px solid var(--c-line);
}

.cell__l {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-text-3);
  line-height: 1;
  white-space: nowrap;
}

.cell__v {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--c-text);
  line-height: 1;
}

.cell__v.gold {
  color: var(--arena-gold);
}

/* Win chance is the number a top-up is judged against, so it is the largest
   thing in the row. */
.chance .cell__v {
  font-size: 23px;
}

.cell__v.bump {
  animation: chest-bump 0.42s cubic-bezier(0.2, 1.6, 0.3, 1);
}

@keyframes chest-bump {
  0% { transform: scale(1); }
  45% { transform: scale(1.14); }
  100% { transform: scale(1); }
}

.meter {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.meter__track {
  height: 6px;
  border-radius: var(--r-chip);
  background: var(--c-bg);
  border: 1px solid var(--c-line);
  overflow: hidden;
}

.meter__fill {
  height: 100%;
  border-radius: var(--r-chip);
  background: var(--arena-gold);
  box-shadow: 0 0 10px var(--arena-glow);
  transition: width var(--d-sheet) var(--e-out);
}

.meter__cap {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--c-text-3);
  line-height: 1;
}

/* ── stake ────────────────────────────────────────────────────────────────── */
.stepper {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px;
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
}

.stepper.disabled {
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
  background: var(--arena-gold-soft);
  color: var(--arena-gold);
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
  letter-spacing: -0.01em;
  color: var(--c-text);
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

/* ── actions ──────────────────────────────────────────────────────────────── */
.actions {
  display: flex;
  align-items: stretch;
  gap: 9px;
}

.cancel {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  color: var(--c-lose);
  cursor: pointer;
}

.cancel.busy {
  cursor: progress;
}

.cancel__spinner {
  display: inline-block;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 2px solid var(--c-lose-soft);
  border-top-color: var(--c-lose);
  animation: cancel-spin 0.7s linear infinite;
}

@keyframes cancel-spin {
  to {
    transform: rotate(360deg);
  }
}

.primary {
  flex: 1 1 auto;
  min-width: 0;
}

@media (prefers-reduced-motion: reduce) {
  .cell__v.bump {
    animation: none;
  }

  .meter__fill {
    transition: none;
  }
}
</style>
