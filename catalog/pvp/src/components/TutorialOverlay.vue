<script setup lang="ts">
import { computed, ref } from 'vue'
import { PhChartPieSlice, PhCrown, PhShieldCheck, PhSword, PhTimer } from '@phosphor-icons/vue'

const emit = defineEmits<{ dismiss: [] }>()

const STEPS = [
  { icon: PhSword, title: 'Enter the arena', body: 'Stake TON to claim your territory on the battle wheel.' },
  { icon: PhChartPieSlice, title: 'Bigger stake, bigger territory', body: 'Your share of the war chest is your slice of the wheel, and your odds of victory. Even the smallest fighter stays on the wheel.' },
  { icon: PhTimer, title: 'The clash countdown', body: 'Once two warriors are in, a 10s countdown charges. Reinforce or retreat until it locks.' },
  { icon: PhCrown, title: 'Winner takes all', body: 'The striker falls on one territory, and that warrior seizes every TON in the chest.' },
  { icon: PhShieldCheck, title: 'Provably fair', body: 'The outcome is sealed before the strike - verify any battle in the shield menu.' },
] as const

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
          <div class="icon"><component :is="current.icon" :size="34" weight="fill" /></div>
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
  background: rgba(7, 12, 19, 0.86);
  animation: veil var(--d-sheet) var(--e-out) both;
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
  animation: pop 0.45s cubic-bezier(0.2, 1.4, 0.4, 1) both;
}

@keyframes pop {
  0% {
    transform: scale(0.4);
    opacity: 0;
  }
  60% {
    transform: scale(1.06);
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

.skip {
  position: absolute;
  top: 12px;
  right: 14px;
  padding: 6px 10px;
  border: none;
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
  display: flex;
  align-items: center;
  justify-content: center;
  width: 62px;
  height: 62px;
  border-radius: var(--r-chip);
  background: var(--arena-gold-soft);
  color: var(--arena-gold);
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
  max-width: 264px;
}

.dots {
  display: flex;
  gap: 8px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-surface-2);
  transition: background var(--d-pop) var(--e-out);
}

.dot.on {
  background: var(--arena-gold);
}

.next {
  width: 100%;
  height: var(--tap);
  border: none;
  border-radius: var(--r-ctl);
  background: var(--arena-gold);
  color: var(--arena-ink);
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
}

.next:active {
  background: var(--arena-gold-press);
}

.step-enter-active,
.step-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}

.step-enter-from {
  opacity: 0;
  transform: translateX(14px);
}

.step-leave-to {
  opacity: 0;
  transform: translateX(-14px);
}

@media (prefers-reduced-motion: reduce) {
  .card,
  .step-enter-active,
  .step-leave-active {
    animation: none;
    transition: none;
  }
}
</style>
