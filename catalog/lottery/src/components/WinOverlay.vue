<script setup lang="ts">
import { computed } from 'vue'
import { formatTon } from '../format'

defineProps<{ payout: number }>()
const emit = defineEmits<{ dismiss: [] }>()

// A fixed spray of gold sparks. Deterministic angles/distances (no Math.random,
// which is unavailable in some sandboxes and would also make the burst jitter on
// re-render): 20 sparks fanned evenly with alternating reach.
const sparks = computed(() =>
  Array.from({ length: 20 }, (_, i) => {
    const ang = (i / 20) * Math.PI * 2
    const reach = 120 + (i % 3) * 46
    return {
      x: Math.cos(ang) * reach,
      y: Math.sin(ang) * reach,
      delay: (i % 5) * 40,
    }
  }),
)
</script>

<template>
  <div class="win" @click="emit('dismiss')">
    <div class="burst" aria-hidden="true">
      <span
        v-for="(s, i) in sparks"
        :key="i"
        class="spark"
        :style="{
          '--x': `${s.x}px`,
          '--y': `${s.y}px`,
          animationDelay: `${s.delay}ms`,
        }"
      />
    </div>

    <div class="content">
      <span class="kicker label">Jackpot</span>
      <h1 class="title gold-text">You won</h1>
      <div class="amount tnum">
        <span class="amount__v gold-text">+{{ formatTon(payout) }}</span>
        <span class="amount__cur gold-text">gram(TON)</span>
      </div>
      <span class="hint">Tap anywhere to continue</span>
    </div>
  </div>
</template>

<style scoped>
.win {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(60% 50% at 50% 42%, rgba(94, 20, 6, 0.72), rgba(15, 2, 3, 0.9));
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

.burst {
  position: absolute;
  top: 42%;
  left: 50%;
  width: 0;
  height: 0;
}

.spark {
  position: absolute;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 30%, var(--gold-hi), var(--gold-2));
  box-shadow: 0 0 8px rgba(247, 206, 85, 0.8);
  animation: fly 0.9s var(--e-out) forwards;
}

@keyframes fly {
  0% {
    transform: translate(0, 0) scale(0.4);
    opacity: 1;
  }
  100% {
    transform: translate(var(--x), var(--y)) scale(1);
    opacity: 0;
  }
}

.content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
  animation: rise 0.45s cubic-bezier(0.2, 1.3, 0.4, 1) both;
}

@keyframes rise {
  0% {
    transform: scale(0.8);
    opacity: 0;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

.kicker {
  color: var(--gold);
  letter-spacing: 0.24em;
}

.title {
  margin: 0;
  font-size: 34px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.amount {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 4px;
}

.amount__v {
  font-size: 40px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.amount__cur {
  font-size: 16px;
  font-weight: 800;
  white-space: nowrap;
}

.hint {
  margin-top: 16px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--lot-text-3);
}

@media (prefers-reduced-motion: reduce) {
  .win,
  .content {
    animation: none;
  }

  .spark {
    display: none;
  }
}
</style>
