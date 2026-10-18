<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { LiveState } from '../composables/useTeleFly'
import type { Phase } from '../net/protocol'

const props = defineProps<{
  /** Non-reactive live holder written by the tick stream; polled here per frame. */
  live: LiveState
  phase: Phase
}>()

/** The betting window the engine runs, used only to size the drain bar. */
const BET_WINDOW_MS = 10_000

const valueEl = ref<HTMLElement | null>(null)
const barEl = ref<HTMLElement | null>(null)

const label = computed(() => {
  if (props.phase === 'BETTING') return 'Betting closes in'
  if (props.phase === 'FLYING') return 'In flight'
  return 'Flew away at'
})

let raf = 0
let lastText = ''
let lastTier = ''

function tier(m: number): string {
  if (m < 2) return 'mid'
  if (m < 10) return 'high'
  return 'huge'
}

// Written straight to the DOM rather than through a ref: the multiplier changes
// ~20 times a second and must never queue a component re-render on the same
// frame the player is reaching for Cash Out.
function frame(): void {
  raf = requestAnimationFrame(frame)
  const el = valueEl.value
  if (!el) return

  let text: string
  let want: string

  if (props.phase === 'BETTING') {
    const left = Math.max(0, props.live.phaseEndsAt - Date.now())
    text = `${Math.ceil(left / 1000)}s`
    want = 'wait'
    if (barEl.value) barEl.value.style.transform = `scaleX(${Math.min(1, left / BET_WINDOW_MS)})`
  } else if (props.phase === 'FLYING') {
    const m = props.live.shownMultiplier
    text = `×${m.toFixed(2)}`
    want = tier(m)
  } else {
    const m = props.live.crashMultiplier ?? props.live.shownMultiplier
    text = `×${m.toFixed(2)}`
    want = 'gone'
  }

  if (text !== lastText) {
    el.textContent = text
    lastText = text
  }
  if (want !== lastTier) {
    el.dataset.tier = want
    lastTier = want
  }
}

onMounted(() => {
  raf = requestAnimationFrame(frame)
})
onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<template>
  <div class="readout" :class="phase.toLowerCase()">
    <span class="readout__label">{{ label }}</span>
    <div ref="valueEl" class="readout__value tnum">×1.00</div>
    <div v-if="phase === 'BETTING'" class="readout__track">
      <div ref="barEl" class="readout__bar" />
    </div>
  </div>
</template>

<style scoped>
.readout {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  pointer-events: none;
}

/* The phase name is the first thing read, so it is never the same colour as the
   number: label tells you where you are, number tells you how much. */
.readout__label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  line-height: 1;
  color: var(--c-text-3);
}

.betting .readout__label {
  color: var(--c-brand);
}

.crashed .readout__label {
  color: var(--c-lose);
}

.readout__value {
  font-size: 64px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.035em;
  color: var(--c-text);
  text-shadow: 0 4px 22px rgba(3, 7, 12, 0.75);
}

.readout__value[data-tier='wait'] {
  color: var(--c-brand);
}

.readout__value[data-tier='high'] {
  color: var(--trail-edge);
}

.readout__value[data-tier='huge'] {
  color: var(--c-win);
}

.readout__value[data-tier='gone'] {
  color: var(--c-lose);
}

/* Drains left to right across the betting window: the countdown digit alone
   does not convey "nearly over" at a glance. */
.readout__track {
  width: 148px;
  height: 4px;
  border-radius: var(--r-chip);
  background: var(--c-line-2);
  overflow: hidden;
}

.readout__bar {
  width: 100%;
  height: 100%;
  border-radius: var(--r-chip);
  background: var(--c-brand);
  transform-origin: left center;
}
</style>
