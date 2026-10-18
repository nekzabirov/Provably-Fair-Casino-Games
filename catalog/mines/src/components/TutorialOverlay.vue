<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  PhBomb,
  PhCoins,
  PhCurrencyCircleDollar,
  PhDiamond,
  PhPlay,
} from '@phosphor-icons/vue'

const emit = defineEmits<{ dismiss: [] }>()

const STEPS = [
  {
    icon: PhCurrencyCircleDollar,
    title: 'Set your bet',
    body: 'Use the minus and plus keys, or tap a quick amount.',
  },
  {
    icon: PhBomb,
    title: 'Choose mines',
    body: 'More mines pay more per pick, and blow up sooner.',
  },
  { icon: PhPlay, title: 'Tap Play', body: 'Your stake is locked in and the round starts.' },
  {
    icon: PhDiamond,
    title: 'Reveal gems',
    body: 'Every safe tile raises your multiplier. The strip up top shows what one more pick pays.',
  },
  {
    icon: PhCoins,
    title: 'Cash out in time',
    body: 'Take the money before you hit a mine. Hit one and the stake is gone.',
  },
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
          <div class="icon"><component :is="current.icon" :size="30" weight="fill" /></div>
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
  background: rgba(11, 16, 23, 0.86);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  animation: veil 0.3s var(--e-out) both;
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
  padding: 28px 22px 22px;
  border-radius: var(--r-sheet);
  background: var(--c-surface);
  border: 1px solid var(--c-line-2);
  box-shadow: var(--sh-2);
  animation: pop 0.4s cubic-bezier(0.2, 1.3, 0.4, 1) both;
}

/* Never from scale 0: a card that grows out of nothing reads as a glitch. */
@keyframes pop {
  0% {
    transform: scale(0.86);
    opacity: 0;
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
  padding: 4px 8px;
  border: none;
  background: none;
  color: var(--c-text-2);
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
  margin-bottom: 2px;
  border-radius: var(--r-ctl);
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
  font-weight: 500;
  line-height: 1.45;
  color: var(--c-text-2);
  max-width: 27ch;
}

.dots {
  display: flex;
  gap: 8px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-surface-2);
  transition: background var(--d-pop) var(--e-out);
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
  color: #04121c;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: var(--sh-cta);
}

.next:active {
  background: var(--c-brand-press);
}

.step-enter-active,
.step-leave-active {
  transition:
    opacity var(--d-pop) var(--e-out),
    transform var(--d-pop) var(--e-out);
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
