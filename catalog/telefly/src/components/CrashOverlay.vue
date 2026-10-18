<script setup lang="ts">
import { PhAirplaneTilt } from '@phosphor-icons/vue'

defineProps<{
  /** The crash multiplier the round flew away at. */
  multiplier: string
}>()
const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="crash" @click="emit('dismiss')">
    <div class="shock" />
    <div class="content">
      <PhAirplaneTilt class="plane" :size="64" weight="fill" />
      <div class="title">Flew away</div>
      <div class="mult tnum">×{{ multiplier }}</div>
      <div class="subtitle">Better luck next round</div>
      <div class="hint">Tap anywhere to continue</div>
    </div>
  </div>
</template>

<style scoped>
.crash {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(11, 16, 23, 0.72);
  animation: veil var(--d-pop) var(--e-out) both;
  cursor: pointer;
}

@keyframes veil {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* One expanding ring, not a fireball: the round is over and the player is about
   to read a number, so the effect must clear the screen fast. */
.shock {
  position: absolute;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 4px solid var(--c-lose);
  animation: shock 0.6s var(--e-out) forwards;
}

@keyframes shock {
  0% {
    transform: scale(0);
    opacity: 0.9;
  }
  100% {
    transform: scale(14);
    opacity: 0;
  }
}

.content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  animation: pop var(--d-pop) var(--e-out) both;
}

@keyframes pop {
  from {
    transform: scale(0.9);
    opacity: 0;
  }
  to {
    transform: scale(1);
    opacity: 1;
  }
}

.plane {
  color: var(--c-lose);
  animation: jolt 0.5s var(--e-out);
}

@keyframes jolt {
  0% {
    transform: translate(0, 0) rotate(0);
  }
  100% {
    transform: translate(16px, -12px) rotate(26deg);
  }
}

.title {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--c-lose);
}

.mult {
  font-size: 52px;
  font-weight: 800;
  letter-spacing: -0.035em;
  line-height: 1;
  color: var(--c-lose);
}

.subtitle {
  font-size: 14px;
  color: var(--c-text-2);
}

.hint {
  margin-top: 14px;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

@media (prefers-reduced-motion: reduce) {
  .shock,
  .content,
  .plane {
    animation: none;
  }
}
</style>
