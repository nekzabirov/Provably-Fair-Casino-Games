<script setup lang="ts">
import { PhTrophy, PhXCircle } from '@phosphor-icons/vue'
import TonIcon from './TonIcon.vue'

defineProps<{
  won: boolean | null
  /** Pre-formatted amount returned on a win. Ignored when the round was lost. */
  amount: string
}>()

const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="result" :class="won ? 'win' : 'lose'" @click="emit('dismiss')">
    <div class="glow" aria-hidden="true" />

    <div class="content">
      <span class="mark">
        <component :is="won ? PhTrophy : PhXCircle" :size="46" weight="fill" />
      </span>

      <div class="title">{{ won ? 'You win' : 'You missed' }}</div>

      <div v-if="won" class="payout">
        <span class="tnum">{{ amount }}</span>
        <TonIcon :size="19" />
      </div>

      <div class="subtitle">
        {{ won ? 'The ball was under your cup' : 'The ball was under another cup' }}
      </div>

      <div class="hint">Tap anywhere to continue</div>
    </div>
  </div>
</template>

<style scoped>
.result {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(10, 7, 5, 0.74);
  animation: veil var(--d-pop) var(--e-out) both;
  cursor: pointer;
}

.glow {
  position: absolute;
  inset: 0;
}

/* Kept tight around the verdict rather than washed across the frame: the warm
   render underneath is the game's identity and must not be tinted whole. */
.win .glow {
  background: radial-gradient(
    circle at 50% 44%,
    rgba(46, 212, 122, 0.3) 0%,
    rgba(46, 212, 122, 0) 42%
  );
}

.lose .glow {
  background: radial-gradient(
    circle at 50% 44%,
    rgba(255, 90, 101, 0.24) 0%,
    rgba(255, 90, 101, 0) 42%
  );
}

.content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
  animation: pop 0.42s var(--e-out) both;
}

.mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 84px;
  height: 84px;
  margin-bottom: 4px;
  border-radius: var(--r-chip);
}

.win .mark {
  background: var(--c-win-soft);
  color: var(--c-win);
}

.lose .mark {
  background: var(--c-lose-soft);
  color: var(--c-lose);
}

.title {
  font-size: 30px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--c-text);
}

.payout {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 26px;
  font-weight: 700;
  color: var(--c-win);
}

.subtitle {
  font-size: 14px;
  color: var(--c-text-2);
}

.hint {
  margin-top: 18px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--c-text-3);
}

@keyframes veil {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes pop {
  from {
    transform: scale(0.88);
    opacity: 0;
  }
  to {
    transform: scale(1);
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .result,
  .content {
    animation: none;
  }
}
</style>
