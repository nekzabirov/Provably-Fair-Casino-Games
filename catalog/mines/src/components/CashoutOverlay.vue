<script setup lang="ts">
import TonIcon from './TonIcon.vue'

defineProps<{
  /** Pre-formatted payout amount. */
  amount: string
}>()
const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="win" @click="emit('dismiss')">
    <div class="glow" />
    <div class="ring" />
    <div class="content">
      <!-- The payout is the celebration. It does not need a mascot next to it. -->
      <div class="title">Cashed out</div>
      <div class="amount tnum">
        <span>+{{ amount }}</span>
        <TonIcon :size="28" />
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
  background: rgba(11, 16, 23, 0.74);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
  animation: veil 0.3s var(--e-out) both;
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
  background: radial-gradient(circle at center, rgba(46, 212, 122, 0.42), rgba(46, 212, 122, 0) 62%);
  animation: glow 0.8s var(--e-out) forwards;
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
  border: 3px solid rgba(46, 212, 122, 0.8);
  animation: ring 0.8s var(--e-out) forwards;
}

@keyframes ring {
  0% {
    transform: scale(0.2);
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
  gap: 10px;
  animation: pop 0.42s cubic-bezier(0.2, 1.3, 0.4, 1) both;
}

@keyframes pop {
  0% {
    transform: scale(0.72);
    opacity: 0;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

.title {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--c-text-2);
}

.amount {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 44px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1;
  color: var(--c-win);
}

.hint {
  margin-top: 18px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--c-text-3);
}

@media (prefers-reduced-motion: reduce) {
  .win,
  .glow,
  .content {
    animation: none;
  }

  .ring {
    display: none;
  }
}
</style>
