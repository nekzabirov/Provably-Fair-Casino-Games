<script setup lang="ts">
import { PhCheckCircle } from '@phosphor-icons/vue'
import TonIcon from './TonIcon.vue'

defineProps<{
  /** Pre-formatted payout amount. */
  amount: string
  /** The multiplier the player cashed out at. */
  multiplier: string
}>()
const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="win" @click="emit('dismiss')">
    <div class="ring" />
    <div class="content">
      <PhCheckCircle class="mark" :size="64" weight="fill" />
      <div class="title tnum">Cashed out ×{{ multiplier }}</div>
      <div class="amount">
        <span class="tnum">+{{ amount }}</span>
        <TonIcon :size="26" />
      </div>
      <div class="hint">Tap anywhere to continue</div>
    </div>
  </div>
</template>

<style scoped>
.win {
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

.ring {
  position: absolute;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  border: 4px solid var(--c-win);
  animation: ring 0.7s var(--e-out) forwards;
}

@keyframes ring {
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
  gap: 8px;
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

.mark {
  color: var(--c-win);
}

.title {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--c-text-2);
}

.amount {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 40px;
  font-weight: 800;
  letter-spacing: -0.035em;
  line-height: 1;
  color: var(--c-win);
}

.hint {
  margin-top: 14px;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

@media (prefers-reduced-motion: reduce) {
  .ring,
  .content {
    animation: none;
  }
}
</style>
