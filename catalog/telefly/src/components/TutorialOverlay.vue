<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { PhAirplaneTakeoff, PhAirplaneTilt, PhCoins, PhTarget } from '@phosphor-icons/vue'

const emit = defineEmits<{ dismiss: [] }>()

const STEPS: { icon: Component; title: string; body: string }[] = [
  { icon: PhTarget, title: 'Place your bet', body: 'During the 10s window, set an amount and tap Bet.' },
  { icon: PhAirplaneTakeoff, title: 'Watch it climb', body: 'The plane takes off and the multiplier keeps rising.' },
  { icon: PhCoins, title: 'Cash out in time', body: 'Tap Cash Out before the plane flies away to bank bet x multiplier.' },
  { icon: PhAirplaneTilt, title: 'Mind the crash', body: 'If it flies away before you cash out, the bet is lost.' },
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
          <component :is="current.icon" class="icon" :size="48" weight="fill" />
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
  background: rgba(11, 16, 23, 0.88);
  animation: veil var(--d-pop) var(--e-out) both;
}

@keyframes veil {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
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
  padding: 30px 22px 22px;
  border-radius: var(--r-sheet);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  box-shadow: var(--sh-2);
  animation: rise var(--d-sheet) var(--e-sheet) both;
}

@keyframes rise {
  from {
    transform: translateY(16px);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.skip {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: var(--tap);
  height: var(--tap);
  padding: 0 10px;
  border: none;
  border-radius: var(--r-chip);
  background: none;
  color: var(--c-text-3);
  font-size: 13px;
  font-weight: 600;
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
  color: var(--c-brand);
}

.title {
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--c-text);
}

.text {
  font-size: 14px;
  line-height: 1.45;
  color: var(--c-text-2);
  max-width: 270px;
}

.dots {
  display: flex;
  gap: 8px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: var(--r-chip);
  background: var(--c-surface-2);
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
  color: #04121C;
  font-size: 16px;
  font-weight: 700;
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
  .card,
  .tut,
  .step-enter-active,
  .step-leave-active {
    animation: none;
    transition: none;
  }
}
</style>
