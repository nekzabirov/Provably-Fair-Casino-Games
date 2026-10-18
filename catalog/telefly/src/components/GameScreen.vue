<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PhAirplaneTilt, PhQuestion, PhShieldCheck } from '@phosphor-icons/vue'
import GameHud from './GameHud.vue'
import FlightCanvas from './FlightCanvas.vue'
import FlightReadout from './FlightReadout.vue'
import PositionBar, { type Position } from './PositionBar.vue'
import ControlsPanel from './ControlsPanel.vue'
import HistoryStrip from './HistoryStrip.vue'
import ErrorScreen from './ErrorScreen.vue'
import CrashOverlay from './CrashOverlay.vue'
import CashoutOverlay from './CashoutOverlay.vue'
import TutorialOverlay from './TutorialOverlay.vue'
import FairnessModal from './FairnessModal.vue'
import MuteButton from './MuteButton.vue'
import { currencyRule, formatAmount, formatPayout, fromMinor, roundToStep, toMinor } from '../currency'
import { useTeleFly } from '../composables/useTeleFly'
import { requestDeposit } from '../config'
import { isMuted, playCashout, setMuted, stopAmbient, unlockAudio } from '../sound'
import { hasSeenTutorial, markTutorialSeen } from '../onboarding'
import { forceHideSplash, markAuthReady } from '../preload'

const {
  connected,
  phase,
  player,
  bet,
  history,
  players,
  crashMultiplier,
  serverSeedHash,
  lastRound,
  fatalError,
  currency,
  live,
  placeBet,
  cashout,
} = useTeleFly()

// App.vue only mounts this for a supported currency (TON), so the rule is always present.
const rule = computed(() => currencyRule(currency.value)!)

const betDraft = ref(rule.value.min)

const presets = computed(() => rule.value.presets.map((v) => ({ value: v, label: formatAmount(v, rule.value) })))

const balanceDisplay = computed(() => {
  const b = player.value?.balance
  return b == null ? '-' : formatAmount(fromMinor(b, rule.value), rule.value)
})

const canBet = computed(() => phase.value === 'BETTING' && bet.value == null && connected.value)
const canCashout = computed(() => phase.value === 'FLYING' && bet.value?.status === 'PLACED')

// Live potential win, sampled off the canvas's eased multiplier ~12/s: fast enough
// to feel live, slow enough that the button label is not re-rendered per frame.
const liveMult = ref(1)
let liveTimer = 0

const primaryVariant = computed<'bet' | 'cashout'>(() => (canCashout.value ? 'cashout' : 'bet'))

const primaryLabel = computed(() => {
  if (canCashout.value && bet.value) {
    const win = bet.value.placeAmount * liveMult.value
    return `Cash Out ${formatPayout(win, rule.value)}`
  }
  if (phase.value === 'BETTING') {
    return bet.value ? 'Bet placed - waiting for takeoff' : `Bet ${formatAmount(betDraft.value, rule.value)}`
  }
  if (phase.value === 'FLYING') return bet.value?.status === 'CASHED' ? 'Cashed out' : 'Round in progress'
  return 'Next round in a moment'
})

// The label ends with an amount only for the Cash Out / Bet actions - show the TON icon then.
const primaryShowCurrency = computed(() => canCashout.value || (phase.value === 'BETTING' && bet.value == null))

const primaryDisabled = computed(() => !(canCashout.value || canBet.value))
const betDisabled = computed(() => !canBet.value)

// ── Player position ───────────────────────────────────────────────────────────
// One derived value answers "is my money in, and what is it worth right now".
const position = computed<Position>(() => {
  const b = bet.value
  if (!b) return phase.value === 'BETTING' ? 'open' : 'idle'
  if (b.status === 'CASHED') return 'cashed'
  if (b.status === 'LOST') return 'lost'
  if (phase.value === 'BETTING') return 'placed'
  if (phase.value === 'FLYING') return 'live'
  return 'lost'
})

const stakeDisplay = computed(() =>
  bet.value ? formatAmount(fromMinor(bet.value.placeAmount, rule.value), rule.value) : '',
)

const positionValue = computed(() => {
  const b = bet.value
  if (!b) return ''
  if (b.status === 'CASHED') return formatPayout(b.settleAmount, rule.value)
  return formatPayout(b.placeAmount * liveMult.value, rule.value)
})

const positionMultiplier = computed(() => (bet.value?.cashoutMultiplier ?? 1).toFixed(2))

// ── Overlays ──────────────────────────────────────────────────────────────────
const crashVisible = ref(false)
const crashMult = ref('')
const shaking = ref(false)

const winVisible = ref(false)
const winAmount = ref('')
const winMult = ref('')

watch(phase, (p, prev) => {
  if (p === 'CRASHED' && prev === 'FLYING') {
    const status = bet.value?.status
    shaking.value = true
    window.setTimeout(() => (shaking.value = false), 450)
    if (status === 'PLACED') {
      crashMult.value = (crashMultiplier.value ?? live.shownMultiplier).toFixed(2)
      // Let the plane's dive + explosion play on the canvas before the banner covers it.
      window.setTimeout(() => {
        if (phase.value === 'CRASHED') crashVisible.value = true
      }, 950)
    }
  }
  if (p === 'BETTING') {
    crashVisible.value = false
    winVisible.value = false
    liveMult.value = 1
  }
})

watch(
  () => bet.value?.status,
  (status, prev) => {
    if (status === 'CASHED' && prev !== 'CASHED' && bet.value) {
      winAmount.value = formatPayout(bet.value.settleAmount, rule.value)
      winMult.value = (bet.value.cashoutMultiplier ?? 1).toFixed(2)
      winVisible.value = true
    }
  },
)

// ── Splash coordination ─────────────────────────────────────────────────────
let authMarked = false
watch(player, (p) => {
  if (p && !authMarked) {
    authMarked = true
    markAuthReady()
  }
})
watch(fatalError, (e) => {
  if (e) forceHideSplash()
})

// ── Audio ─────────────────────────────────────────────────────────────────────
const muted = ref(isMuted())
function onToggleMute() {
  muted.value = !muted.value
  setMuted(muted.value)
}
function unlockOnce() {
  unlockAudio()
  window.removeEventListener('pointerdown', unlockOnce)
}

// ── Tutorial ────────────────────────────────────────────────────────────────
const tutorialOpen = ref(false)
function onHelp() {
  unlockAudio()
  tutorialOpen.value = true
}
function onTutorialDismiss() {
  markTutorialSeen()
  tutorialOpen.value = false
}

// ── Provably-fair modal ─────────────────────────────────────────────────────
const fairnessOpen = ref(false)

onMounted(() => {
  window.addEventListener('pointerdown', unlockOnce)
  tutorialOpen.value = !hasSeenTutorial()
  liveTimer = window.setInterval(() => {
    if (phase.value === 'FLYING') liveMult.value = live.shownMultiplier
  }, 80)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', unlockOnce)
  window.clearInterval(liveTimer)
  stopAmbient()
})

// ── Actions ───────────────────────────────────────────────────────────────────
function onBetChange(delta: number) {
  if (!canBet.value) return
  betDraft.value = Math.max(rule.value.min, roundToStep(betDraft.value + delta * rule.value.step, rule.value))
}
function onPreset(value: number) {
  if (canBet.value) betDraft.value = value
}
function onPrimary() {
  if (canCashout.value) {
    playCashout()
    cashout()
  } else if (canBet.value) {
    const minor = toMinor(betDraft.value, rule.value)
    if (player.value?.balance != null && minor > player.value.balance) {
      requestDeposit()
      return
    }
    unlockAudio()
    placeBet(minor)
  }
}
</script>

<template>
  <ErrorScreen v-if="fatalError" title="Session error" :detail="fatalError" />
  <div v-else class="screen" :class="[phase.toLowerCase(), { shake: shaking }]">
    <div class="flight-bg"><FlightCanvas :live="live" /></div>

    <div class="ui">
      <GameHud :balance="balanceDisplay">
        <template #actions>
          <button class="icon-btn press" type="button" aria-label="How to play" @click="onHelp">
            <PhQuestion :size="20" weight="bold" />
          </button>
          <button class="icon-btn press" type="button" aria-label="Provably fair" @click="fairnessOpen = true">
            <PhShieldCheck :size="20" weight="bold" />
          </button>
          <MuteButton :muted="muted" @toggle="onToggleMute" />
        </template>
      </GameHud>

      <HistoryStrip :history="history" />

      <div class="play">
        <div class="players-chip">
          <PhAirplaneTilt :size="14" weight="fill" />
          <span class="players-chip__n tnum">{{ players }}</span>
        </div>

        <div class="readout-slot">
          <FlightReadout :live="live" :phase="phase" />
        </div>
      </div>

      <PositionBar
        :status="position"
        :stake="stakeDisplay"
        :value="positionValue"
        :multiplier="positionMultiplier"
      />

      <ControlsPanel
        :bet="formatAmount(betDraft, rule)"
        :presets="presets"
        :primary-label="primaryLabel"
        :primary-variant="primaryVariant"
        :primary-show-currency="primaryShowCurrency"
        :inputs-disabled="betDisabled"
        :primary-disabled="primaryDisabled"
        @bet-change="onBetChange"
        @preset="onPreset"
        @primary="onPrimary"
      />
    </div>

    <CrashOverlay v-if="crashVisible" :multiplier="crashMult" @dismiss="crashVisible = false" />
    <CashoutOverlay
      v-if="winVisible"
      :amount="winAmount"
      :multiplier="winMult"
      @dismiss="winVisible = false"
    />
    <TutorialOverlay v-if="tutorialOpen" @dismiss="onTutorialDismiss" />
    <FairnessModal
      v-if="fairnessOpen"
      :server-seed-hash="serverSeedHash"
      :last-round="lastRound"
      @close="fairnessOpen = false"
    />
  </div>
</template>

<style scoped>
/* Real high-altitude dawn: dark sky up top for the multiplier to sit against,
   a warm cloud deck near the horizon for the trail to climb over. The gradient
   on top deepens the upper sky so the giant readout always has contrast. */
.screen {
  position: relative;
  width: 100%;
  height: 100dvh;
  overflow: hidden;
  background:
    linear-gradient(180deg, rgba(11, 16, 23, 0.72) 0%, rgba(11, 16, 23, 0.12) 42%, rgba(11, 16, 23, 0) 66%),
    var(--c-bg) url('/env.webp') center / cover no-repeat;
}

/* Phase tint on the edges: peripheral confirmation of where the round is, read
   without moving the eye off the multiplier. */
.screen::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  box-shadow: inset 0 0 90px rgba(3, 7, 12, 0);
  transition: box-shadow var(--d-pop) var(--e-out);
}

.screen.crashed::after {
  box-shadow: inset 0 0 90px rgba(255, 90, 101, 0.22);
}

.screen.betting::after {
  box-shadow: inset 0 0 90px rgba(42, 159, 232, 0.12);
}

.flight-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.ui {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
}

.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--tap);
  height: var(--tap);
  padding: 0;
  border-radius: var(--r-chip);
  border: 1px solid var(--c-line);
  background: rgba(21, 30, 42, 0.72);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: var(--c-text-2);
  cursor: pointer;
}

.play {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
}

/* The readout sits at the top of the play area, with the altitude rungs and the
   climbing trail filling everything below it - no empty middle. */
.readout-slot {
  position: absolute;
  top: 8px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
}

/* Live bettors in the round. */
.players-chip {
  position: absolute;
  top: 10px;
  left: var(--pad-x);
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 11px;
  border-radius: var(--r-chip);
  background: rgba(21, 30, 42, 0.72);
  border: 1px solid var(--c-line);
  color: var(--c-text-2);
  pointer-events: none;
}

.players-chip__n {
  font-size: 13px;
  font-weight: 700;
  line-height: 1;
  color: var(--c-text);
}

.screen.shake {
  animation: screen-shake 0.45s var(--e-in-out);
}

@keyframes screen-shake {
  0%,
  100% {
    transform: translate(0, 0);
  }
  15% {
    transform: translate(-6px, 3px);
  }
  35% {
    transform: translate(6px, -3px);
  }
  55% {
    transform: translate(-4px, -2px);
  }
  75% {
    transform: translate(4px, 2px);
  }
  90% {
    transform: translate(-2px, 1px);
  }
}

@media (prefers-reduced-transparency: reduce) {
  .icon-btn,
  .players-chip {
    background: var(--c-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .screen.shake {
    animation: none;
  }

  .screen::after {
    transition: none;
  }
}
</style>
