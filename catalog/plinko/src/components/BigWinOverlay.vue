<script setup lang="ts">
import TonIcon from './TonIcon.vue'

defineProps<{
  /** Pre-formatted payout amount. */
  amount: string
  /** Display multiplier, e.g. "×127". */
  multiplier: string
}>()
const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="win" @click="emit('dismiss')">
    <div class="glow" />
    <div class="ring" />
    <div class="content">
      <div class="mult">{{ multiplier }}</div>
      <div class="title">BIG WIN</div>
      <div class="amount">+{{ amount }}&nbsp;<TonIcon :size="28" /></div>
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
  background: rgba(6, 11, 17, 0.72);
  animation: veil 0.35s ease-out both;
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

.glow {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center, var(--c-gold-soft), rgba(6, 11, 17, 0));
  animation: glow 0.8s ease-out forwards;
}

@keyframes glow {
  0% {
    opacity: 0;
  }
  18% {
    opacity: 1;
  }
  100% {
    opacity: 0;
  }
}

.ring {
  position: absolute;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  border: 4px solid var(--c-gold);
  animation: ring 0.8s ease-out forwards;
}

@keyframes ring {
  0% {
    transform: scale(0);
    opacity: 1;
  }
  100% {
    transform: scale(15);
    opacity: 0;
  }
}

.content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  animation: pop 0.5s cubic-bezier(0.2, 1.4, 0.4, 1) both;
}

@keyframes pop {
  0% {
    transform: scale(0.3);
    opacity: 0;
  }
  60% {
    transform: scale(1.12);
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

.mult {
  font-size: 64px;
  font-weight: 800;
  line-height: 1;
  color: var(--c-gold);
  text-shadow: 0 0 32px rgba(255, 197, 61, 0.5);
  animation: bob 0.6s ease-out;
}

@keyframes bob {
  0% {
    transform: translateY(14px) scale(0.7);
  }
  60% {
    transform: translateY(-6px) scale(1.08);
  }
  100% {
    transform: translateY(0) scale(1);
  }
}

.title {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 0.18em;
  color: var(--c-text);
}

.amount {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  font-weight: 800;
  color: var(--c-win);
  text-shadow: none;
}

.hint {
  margin-top: 14px;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

@media (prefers-reduced-motion: reduce) {
  .glow,
  .ring,
  .content,
  .mult,
  .hint {
    animation: none;
  }
}
</style>
