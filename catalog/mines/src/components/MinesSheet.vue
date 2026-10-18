<script setup lang="ts">
import { computed, ref } from 'vue'
import { multiplierAt } from '../multiplier'

const props = defineProps<{ value: number }>()
const emit = defineEmits<{ select: [n: number]; close: [] }>()

const local = ref(props.value)

// The whole point of this sheet is the trade-off, so show it as the slider
// moves: more mines means a bigger step per pick and a worse chance of getting
// one. Without these two numbers the slider is an unlabelled risk dial.
const perPick = computed(() => multiplierAt(local.value, 1))
const safeChance = computed(() => ((25 - local.value) / 25) * 100)
</script>

<template>
  <div class="root" @click.self="emit('close')">
    <div class="sheet">
      <div class="grip" />

      <div class="head">
        <span class="title">Mines</span>
        <span class="val tnum">{{ local }}</span>
      </div>

      <div class="stats">
        <div class="stat">
          <span class="stat__l">per safe pick</span>
          <span class="stat__v tnum">×{{ perPick.toFixed(2) }}</span>
        </div>
        <div class="stat">
          <span class="stat__l">first pick is safe</span>
          <span class="stat__v tnum">{{ safeChance.toFixed(0) }}%</span>
        </div>
      </div>

      <input
        class="slider"
        type="range"
        min="1"
        max="24"
        step="1"
        aria-label="Number of mines"
        :value="local"
        @input="local = Number(($event.target as HTMLInputElement).value)"
      />
      <div class="scale">
        <span>1</span>
        <span>24</span>
      </div>

      <button class="apply press" type="button" @click="emit('select', local)">Confirm</button>
    </div>
  </div>
</template>

<style scoped>
.root {
  position: absolute;
  inset: 0;
  z-index: 25;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  background: rgba(11, 16, 23, 0.66);
  animation: veil 0.22s var(--e-out) both;
}

@keyframes veil {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.sheet {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 10px var(--pad-x) calc(var(--safe-bottom) + 18px);
  background: var(--c-surface);
  border-top: 1px solid var(--c-line-2);
  border-radius: var(--r-sheet) var(--r-sheet) 0 0;
  box-shadow: var(--sh-2);
  /* Slides from the edge it is anchored to, so dismissing reads as putting it
     back rather than blinking it away. */
  animation: rise var(--d-sheet) var(--e-sheet) both;
}

@keyframes rise {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}

.grip {
  align-self: center;
  width: 38px;
  height: 4px;
  border-radius: var(--r-chip);
  background: var(--c-line-2);
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.title {
  font-size: 17px;
  font-weight: 700;
  color: var(--c-text);
}

.val {
  font-size: 26px;
  font-weight: 700;
  line-height: 1;
  color: var(--c-lose);
}

.stats {
  display: flex;
  gap: 9px;
}

.stat {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 11px 13px;
  border-radius: var(--r-ctl);
  background: var(--c-bg-2);
  border: 1px solid var(--c-line);
}

.stat__l {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-text-3);
}

.stat__v {
  font-size: 19px;
  font-weight: 700;
  line-height: 1;
  color: var(--c-text);
}

.slider {
  width: 100%;
  height: 28px;
  margin: 0;
  appearance: none;
  -webkit-appearance: none;
  background: transparent;
}

.slider::-webkit-slider-runnable-track {
  height: 5px;
  border-radius: var(--r-chip);
  background: var(--c-surface-2);
}

.slider::-moz-range-track {
  height: 5px;
  border-radius: var(--r-chip);
  background: var(--c-surface-2);
}

/* A 26px thumb is the smallest that stays comfortable to drag with a thumb on
   a phone; the track stays thin so the value is still readable underneath. */
.slider::-webkit-slider-thumb {
  appearance: none;
  -webkit-appearance: none;
  width: 26px;
  height: 26px;
  margin-top: -10.5px;
  border-radius: 50%;
  border: 3px solid var(--c-surface);
  background: var(--c-lose);
  box-shadow: var(--sh-1);
}

.slider::-moz-range-thumb {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 3px solid var(--c-surface);
  background: var(--c-lose);
  box-shadow: var(--sh-1);
}

.scale {
  display: flex;
  justify-content: space-between;
  margin-top: -10px;
  font-size: 11px;
  font-weight: 600;
  color: var(--c-text-3);
}

.apply {
  height: 52px;
  border: none;
  border-radius: var(--r-ctl);
  background: var(--c-brand);
  color: #04121c;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: var(--sh-cta);
}

.apply:active {
  background: var(--c-brand-press);
}

@media (prefers-reduced-motion: reduce) {
  .root,
  .sheet {
    animation: none;
  }
}
</style>
