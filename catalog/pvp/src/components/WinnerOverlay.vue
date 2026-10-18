<script setup lang="ts">
import { computed } from 'vue'
import { PhCrown } from '@phosphor-icons/vue'
import TonIcon from './TonIcon.vue'

const props = defineProps<{
  /** 'win' = you won, 'lose' = you lost, 'spectator' = you weren't in. */
  outcome: 'win' | 'lose' | 'spectator'
  winnerUsername: string
  winnerProfilePic?: string | null
  /** Pre-formatted pot / payout. */
  pot: string
  winningNumber: number
}>()
const emit = defineEmits<{ dismiss: [] }>()

const initial = computed(() => (props.winnerUsername[0] ?? '?').toUpperCase())
</script>

<template>
  <div class="ov" :class="outcome" @click="emit('dismiss')">
    <div v-if="outcome === 'win'" class="rays" />
    <div class="glow" :class="outcome" />
    <div class="content">
      <div class="champ">
        <PhCrown class="crown" :size="34" weight="fill" />
        <div class="ava" :class="outcome">
          <img v-if="winnerProfilePic" :src="winnerProfilePic" alt="" />
          <span v-else class="ava__i">{{ initial }}</span>
        </div>
        <div class="num">{{ Math.round(winningNumber) }}</div>
      </div>

      <template v-if="outcome === 'win'">
        <div class="title win">VICTORY</div>
        <div class="spoils">You seize the war chest</div>
        <div class="amount">+{{ pot }} <TonIcon :size="26" /></div>
      </template>
      <template v-else-if="outcome === 'lose'">
        <div class="title lose">DEFEATED</div>
        <div class="spoils">{{ winnerUsername }} took the chest</div>
        <div class="amount muted">{{ pot }} <TonIcon :size="16" /></div>
      </template>
      <template v-else>
        <div class="title spectate">{{ winnerUsername }} wins</div>
        <div class="amount muted">{{ pot }} <TonIcon :size="16" /> claimed</div>
      </template>
      <div class="hint">Tap to continue</div>
    </div>
  </div>
</template>

<style scoped>
.ov {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: rgba(3, 7, 12, 0.84);
  animation: veil 0.28s var(--e-out) both;
  cursor: pointer;
}

@keyframes veil {
  from { opacity: 0; }
}

.rays {
  position: absolute;
  width: 240vmax;
  height: 240vmax;
  background: repeating-conic-gradient(
    from 0deg,
    var(--arena-ray) 0deg 7deg,
    transparent 7deg 15deg
  );
  /* Faded out towards the middle so the champion and the payout stay the two
     things being read, not the light show behind them. */
  mask-image: radial-gradient(circle at center, transparent 22%, #000 62%);
  -webkit-mask-image: radial-gradient(circle at center, transparent 22%, #000 62%);
  animation: spin 16s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.glow {
  position: absolute;
  inset: 0;
  animation: glow 1s ease-out forwards;
}
.glow.win { background: radial-gradient(circle at center, rgba(255, 197, 61, 0.45), transparent 70%); }
.glow.lose { background: radial-gradient(circle at center, rgba(255, 90, 101, 0.4), transparent 70%); }
.glow.spectator { background: radial-gradient(circle at center, rgba(107, 124, 145, 0.36), transparent 70%); }

@keyframes glow {
  0% { opacity: 0; }
  20% { opacity: 1; }
  100% { opacity: 0.25; }
}

.content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  animation: pop 0.5s cubic-bezier(0.2, 1.5, 0.4, 1) both;
}

@keyframes pop {
  0% { transform: scale(0.3); opacity: 0; }
  55% { transform: scale(1.12); }
  100% { transform: scale(1); opacity: 1; }
}

/* Champion avatar with crown + the winning number as a badge. */
.champ {
  position: relative;
  margin-bottom: 6px;
}

.crown {
  position: absolute;
  top: -26px;
  left: 50%;
  transform: translateX(-50%) rotate(-6deg);
  color: var(--arena-gold);
  filter: drop-shadow(0 2px 4px rgba(3, 7, 12, 0.7));
}

.ava {
  width: 116px;
  height: 116px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--c-surface-2);
  border: 4px solid var(--arena-gold);
  box-shadow: 0 0 40px var(--arena-glow);
}

.ava.lose,
.ava.spectator {
  border-color: var(--c-text-3);
  box-shadow: none;
  filter: saturate(0.7);
}

.ava img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.ava__i {
  font-size: 48px;
  font-weight: 700;
  color: var(--c-text);
}

.num {
  position: absolute;
  bottom: -10px;
  left: 50%;
  transform: translateX(-50%);
  min-width: 44px;
  padding: 3px 11px;
  border-radius: var(--r-chip);
  background: var(--c-bg);
  border: 2px solid var(--arena-gold);
  color: var(--arena-gold);
  font-size: 19px;
  font-weight: 700;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.win.title {
  animation: slam 0.5s cubic-bezier(0.2, 1.6, 0.3, 1) both;
}

@keyframes slam {
  0% { transform: scale(2.4); opacity: 0; letter-spacing: 0.6em; }
  100% { transform: scale(1); opacity: 1; letter-spacing: 0.12em; }
}

.title {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--c-text);
}
.title.win { color: var(--arena-gold); text-shadow: 0 0 24px var(--arena-glow); }
.title.lose { color: var(--c-lose); }
.title.spectate { font-size: 22px; letter-spacing: 0.04em; text-transform: none; }

.spoils {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text-2);
}

.amount {
  margin-top: 4px;
  font-size: 34px;
  font-weight: 700;
  color: var(--c-win);
  text-shadow: 0 0 20px var(--c-win-soft);
  font-variant-numeric: tabular-nums;
}
.amount.muted { font-size: 18px; color: var(--c-text-2); text-shadow: none; }

.hint {
  margin-top: 16px;
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--c-text-3);
}

@media (prefers-reduced-motion: reduce) {
  .rays, .glow, .content, .win.title {
    animation: none;
  }
}
</style>
