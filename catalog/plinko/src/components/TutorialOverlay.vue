<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import {
  PhArrowsOutLineVertical,
  PhCircleNotch,
  PhCoins,
  PhLightning,
  PhShieldCheck,
} from '@phosphor-icons/vue'

const emit = defineEmits<{ dismiss: [] }>()

const STEPS: { icon: Component; title: string; body: string }[] = [
  { icon: PhCoins, title: 'Set your bet', body: 'Use minus / plus to choose how much to wager.' },
  {
    icon: PhLightning,
    title: 'Pick the risk',
    body: 'High risk piles the payouts at the edges and empties the middle.',
  },
  {
    icon: PhArrowsOutLineVertical,
    title: 'Choose rows',
    body: 'More rows means more slots and wilder edge multipliers.',
  },
  {
    icon: PhCircleNotch,
    title: 'Drop the ball',
    body: 'It bounces down the pegs and lands on one multiplier. That is your payout.',
  },
  {
    icon: PhShieldCheck,
    title: 'Provably fair',
    body: 'Every path is sealed before the drop. Verify it from the shield in the header.',
  },
]

const step = ref(0)
const current = computed(() => STEPS[step.value])
const isLast = computed(() => step.value === STEPS.length - 1)

function next() {
  if (isLast.value) emit('dismiss')
  else step.value++
}
</script>

<template>
  <div class="tut">
    <div class="card">
      <button class="skip press" type="button" @click="emit('dismiss')">Skip</button>

      <Transition name="step" mode="out-in">
        <div :key="step" class="body">
          <div class="icon">
            <component :is="current.icon" :size="34" weight="fill" />
          </div>
          <div class="title">{{ current.title }}</div>
          <div class="text">{{ current.body }}</div>
        </div>
      </Transition>

      <div class="dots">
        <span v-for="i in STEPS.length" :key="i" class="dot" :class="{ on: i - 1 === step }" />
      </div>

      <button class="next press" type="button" @click="next">
        {{ isLast ? 'Got it' : 'Next' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.tut {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(6, 11, 17, 0.84);
  animation: veil var(--d-pop) var(--e-out) both;
}

@keyframes veil {
  from {
    opacity: 0;
  }
}

.card {
  position: relative;
  width: 100%;
  max-width: 340px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  padding: 28px 22px 22px;
  border-radius: var(--r-sheet);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  box-shadow: var(--sh-2);
  animation: rise var(--d-sheet) var(--e-sheet) both;
}

@keyframes rise {
  from {
    transform: translateY(18px);
    opacity: 0;
  }
}

.skip {
  position: absolute;
  top: 10px;
  right: 10px;
  height: 34px;
  padding: 0 10px;
  border: none;
  background: none;
  color: var(--c-text-3);
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}

.body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}

.icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: var(--r-chip);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.title {
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--c-text);
}

.text {
  font-size: 14px;
  line-height: 1.45;
  color: var(--c-text-2);
  max-width: 262px;
}

.dots {
  display: flex;
  gap: 8px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: var(--r-chip);
  background: var(--c-line-2);
  transition: background var(--d-press) var(--e-out);
}

.dot.on {
  background: var(--c-brand);
}

.next {
  width: 100%;
  height: 52px;
  border: none;
  border-radius: var(--r-ctl);
  background: var(--c-brand);
  box-shadow: var(--sh-cta);
  color: #04121C;
  font-size: 16px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}

.next:active {
  background: var(--c-brand-press);
}

.step-enter-active,
.step-leave-active {
  transition:
    opacity var(--d-press) var(--e-out),
    transform var(--d-press) var(--e-out);
}

.step-enter-from {
  opacity: 0;
  transform: translateX(12px);
}

.step-leave-to {
  opacity: 0;
  transform: translateX(-12px);
}

@media (prefers-reduced-motion: reduce) {
  .tut,
  .card,
  .step-enter-active,
  .step-leave-active {
    animation: none;
    transition: none;
  }
}
</style>
