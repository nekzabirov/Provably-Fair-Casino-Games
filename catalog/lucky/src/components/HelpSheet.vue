<script setup lang="ts">
import { PhCoins, PhEye, PhHandPointing, PhShieldCheck, PhTrophy } from '@phosphor-icons/vue'

const emit = defineEmits<{ close: [] }>()

const STEPS = [
  {
    icon: PhCoins,
    title: 'Set your stake',
    body: 'Tap minus or plus, or hold either one to move faster.',
  },
  { icon: PhEye, title: 'Watch the ball', body: 'The dealer shows you the cup, then shuffles.' },
  { icon: PhHandPointing, title: 'Pick a cup', body: 'Tap a cup on the table, or use the 1 / 2 / 3 buttons.' },
  {
    icon: PhTrophy,
    title: 'Right cup pays 2.00x',
    body: 'One cup of the three holds the ball, so the odds of finding it are 1 in 3. A wrong pick loses the stake.',
  },
  {
    icon: PhShieldCheck,
    title: 'Provably fair',
    body: 'The winning cup is fixed before you pick and can be re-derived from the seeds afterwards.',
  },
] as const
</script>

<template>
  <div class="wrap" role="dialog" aria-modal="true" aria-label="How to play">
    <div class="scrim" @click="emit('close')" />

    <section class="sheet">
      <div class="grip" aria-hidden="true" />
      <h2 class="title">How to play</h2>

      <ul class="steps">
        <li v-for="s in STEPS" :key="s.title" class="step">
          <span class="step__icon"><component :is="s.icon" :size="20" weight="fill" /></span>
          <div class="step__text">
            <span class="step__title">{{ s.title }}</span>
            <span class="step__body">{{ s.body }}</span>
          </div>
        </li>
      </ul>

      <button class="done press" type="button" @click="emit('close')">Got it</button>
    </section>
  </div>
</template>

<style scoped>
.wrap {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.scrim {
  position: absolute;
  inset: 0;
  background: rgba(10, 7, 5, 0.66);
  animation: veil var(--d-sheet) var(--e-sheet) both;
}

.sheet {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 10px var(--pad-x) calc(var(--safe-bottom) + 16px);
  border-radius: var(--r-sheet) var(--r-sheet) 0 0;
  background: var(--warm-bg-2);
  border-top: 1px solid var(--c-line-2);
  box-shadow: var(--sh-2);
  animation: rise var(--d-sheet) var(--e-sheet) both;
}

.grip {
  align-self: center;
  width: 36px;
  height: 4px;
  border-radius: var(--r-chip);
  background: var(--c-line-2);
}

.title {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--c-text);
}

.steps {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.step__icon {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-ctl);
  background: var(--copper-soft);
  color: var(--copper);
}

.step__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.step__title {
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text);
}

.step__body {
  font-size: 13px;
  line-height: 1.4;
  color: var(--c-text-2);
}

.done {
  height: 52px;
  border: none;
  border-radius: var(--r-ctl);
  background: var(--copper);
  color: var(--copper-ink);
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
}

.done:active {
  background: var(--copper-press);
}

@keyframes veil {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes rise {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .scrim,
  .sheet {
    animation: none;
  }
}
</style>
