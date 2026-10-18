<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PhQuestion, PhShieldCheck } from '@phosphor-icons/vue'
import GameHud from './GameHud.vue'
import WheelCanvas from './WheelCanvas.vue'
import PlayersStrip from './PlayersStrip.vue'
import ControlsPanel from './ControlsPanel.vue'
import ErrorScreen from './ErrorScreen.vue'
import WinnerOverlay from './WinnerOverlay.vue'
import TutorialOverlay from './TutorialOverlay.vue'
import FairnessModal from './FairnessModal.vue'
import MuteButton from './MuteButton.vue'
import { TON, formatAmount, fromMinor, roundToStep, toMinor } from '../currency'
import { requestDeposit } from '../config'
import { usePvp } from '../composables/usePvp'
import { winnerIndex } from '../fairness'
import { isMuted, playBet, playLose, playSpin, playWin, setMuted, unlockAudio } from '../sound'
import { hasSeenTutorial, markTutorialSeen } from '../onboarding'
import { forceHideSplash, markAuthReady } from '../preload'
import type { SpinFrame } from '../net/protocol'

const {
  connected,
  error,
  fatalError,
  player,
  phase,
  roundNonce,
  serverSeedHash,
  phaseEndsAt,
  pot,
  roster,
  bet,
  lastSpin,
  busy,
  onSpin,
  placeBet,
  cancel,
} = usePvp()

// ── Bet amount (persisted) ────────────────────────────────────────────────────
function loadBet(): number {
  try {
    const v = Number(localStorage.getItem('pvp.bet'))
    return Number.isFinite(v) && v >= TON.min ? v : TON.min
  } catch {
    return TON.min
  }
}
function saveBet(v: number): void {
  try {
    localStorage.setItem('pvp.bet', String(v))
  } catch {
    /* ignore */
  }
}
const amount = ref(loadBet())

// Matches the engine's PVP_COUNTDOWN_MS - only used to fill the charge ring.
const COUNTDOWN_MS = 10_000

// ── Derived display ──────────────────────────────────────────────────────────
const locked = computed(() => phase.value === 'SPINNING' || phase.value === 'PAYOUT')
const balanceDisplay = computed(() =>
  player.value ? formatAmount(fromMinor(player.value.balance, TON), TON) : '-',
)
const potDisplay = computed(() => formatAmount(fromMinor(pot.value, TON), TON))
const stakeDisplay = computed(() => formatAmount(fromMinor(bet.value?.stake ?? 0, TON), TON))
// Your share of the pot is your chance of taking it: the wheel is drawn from the
// same ratio, so one number explains both the odds and the picture.
const chancePct = computed(() => {
  const stake = bet.value?.stake ?? 0
  if (pot.value <= 0 || stake <= 0) return 0
  return (stake / pot.value) * 100
})
const chanceDisplay = computed(() => {
  const pct = chancePct.value
  if (pct <= 0) return '0%'
  return pct < 1 ? '<1%' : `${Math.round(pct)}%`
})
const youOrder = computed(() => bet.value?.order ?? null)

const primaryVariant = computed<'bet' | 'locked'>(() => (locked.value ? 'locked' : 'bet'))
const primaryLabel = computed(() => {
  if (locked.value) return phase.value === 'SPINNING' ? 'Strike!' : 'Battle underway'
  return bet.value ? `Reinforce + ${formatAmount(amount.value, TON)}` : 'Enter battle'
})
const primaryDisabled = computed(() => locked.value || !connected.value)
const canCancel = computed(() => !!bet.value && !locked.value)

// ── Toast ──────────────────────────────────────────────────────────────────────
const toastMsg = ref<string | null>(null)
let toastTimer = 0
function showToast(msg: string) {
  toastMsg.value = msg
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => (toastMsg.value = null), 2600)
}
watch(error, (e) => {
  if (e) showToast(e)
})

// ── Actions ──────────────────────────────────────────────────────────────────
function onBetChange(delta: number) {
  amount.value = Math.max(TON.min, roundToStep(amount.value + delta * TON.step, TON))
}
function onBetSet(value: number) {
  amount.value = Math.max(TON.min, roundToStep(value, TON))
}
function onPrimary() {
  if (locked.value || !connected.value || busy.value) return
  unlockAudio()
  const minor = toMinor(amount.value, TON)
  const balance = player.value?.balance
  if (balance != null && minor > balance) {
    showToast('Not enough TON - top up to play')
    requestDeposit()
    return
  }
  playBet()
  placeBet(minor)
  saveBet(amount.value)
}
function onCancel() {
  if (!canCancel.value || busy.value) return
  cancel()
}

// ── Wheel + winner overlay ───────────────────────────────────────────────────
const wheel = ref<InstanceType<typeof WheelCanvas> | null>(null)
const winnerVisible = ref(false)
const winnerOutcome = ref<'win' | 'lose' | 'spectator'>('spectator')
const winnerSpin = ref<SpinFrame | null>(null)

let unsubscribe: (() => void) | null = null

function handleSpin(s: SpinFrame) {
  const myOrder = bet.value?.order ?? null
  const idx = winnerIndex(s.winningNumber / 100, s.roster.map((seat) => seat.stake))
  const winnerSeatOrder = s.roster[idx]?.order ?? -1
  winnerOutcome.value = myOrder == null ? 'spectator' : winnerSeatOrder === myOrder ? 'win' : 'lose'
  winnerSpin.value = s
  unlockAudio()
  playSpin()
  triggerImpact()
  wheel.value?.spinTo(winnerSeatOrder, s.winningNumber)
}

// The strike itself is the answer; the overlay only confirms it. Holding the
// overlay back for a beat lets the player watch the striker land and the winning
// territory light up, instead of having the wheel snatched away at the moment it
// finally says something.
const LANDING_BEAT_MS = 900
let landingTimer = 0

function onLanded() {
  triggerImpact()
  if (winnerOutcome.value === 'win') playWin()
  else if (winnerOutcome.value === 'lose') playLose()
  window.clearTimeout(landingTimer)
  landingTimer = window.setTimeout(() => (winnerVisible.value = true), LANDING_BEAT_MS)
}

// Whole-screen jolt on lock and on the pointer strike.
const impact = ref(false)
let impactTimer = 0
function triggerImpact() {
  impact.value = false
  window.clearTimeout(impactTimer)
  requestAnimationFrame(() => {
    impact.value = true
    impactTimer = window.setTimeout(() => (impact.value = false), 480)
  })
}

// A fresh round clears the celebration.
watch(phase, (p) => {
  if (p === 'WAITING') {
    window.clearTimeout(landingTimer)
    winnerVisible.value = false
  }
})

// ── Splash / audio / tutorial / fairness ──────────────────────────────────────
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

const muted = ref(isMuted())
function onToggleMute() {
  muted.value = !muted.value
  setMuted(muted.value)
}
function unlockOnce() {
  unlockAudio()
  window.removeEventListener('pointerdown', unlockOnce)
}

const tutorialOpen = ref(false)
function onHelp() {
  unlockAudio()
  tutorialOpen.value = true
}
function onTutorialDismiss() {
  markTutorialSeen()
  tutorialOpen.value = false
}

const fairnessOpen = ref(false)

onMounted(() => {
  window.addEventListener('pointerdown', unlockOnce)
  tutorialOpen.value = !hasSeenTutorial()
  unsubscribe = onSpin(handleSpin)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', unlockOnce)
  window.clearTimeout(toastTimer)
  window.clearTimeout(impactTimer)
  window.clearTimeout(landingTimer)
  unsubscribe?.()
})
</script>

<template>
  <ErrorScreen v-if="fatalError" title="Session error" :detail="fatalError" />
  <div v-else class="screen" :class="{ impact }">
    <GameHud :balance="balanceDisplay">
      <template #actions>
        <button class="help press" type="button" aria-label="How to play" @click="onHelp">
          <PhQuestion :size="20" weight="bold" />
        </button>
        <button
          class="help press"
          type="button"
          aria-label="Provably fair"
          @click="fairnessOpen = true"
        >
          <PhShieldCheck :size="20" weight="bold" />
        </button>
        <MuteButton :muted="muted" @toggle="onToggleMute" />
      </template>
    </GameHud>

    <WheelCanvas
      ref="wheel"
      :roster="roster"
      :phase="phase"
      :phase-ends-at="phaseEndsAt"
      :countdown-ms="COUNTDOWN_MS"
      :you-order="youOrder"
      @landed="onLanded"
    />

    <PlayersStrip :roster="roster" :pot="pot" :you-order="youOrder" />

    <ControlsPanel
      :bet="formatAmount(amount, TON)"
      :presets="TON.presets"
      :pot="potDisplay"
      :stake="stakeDisplay"
      :chance="chanceDisplay"
      :chance-pct="chancePct"
      :primary-label="primaryLabel"
      :primary-variant="primaryVariant"
      :primary-disabled="primaryDisabled"
      :busy="busy"
      :inputs-disabled="locked"
      :can-cancel="canCancel"
      @bet-change="onBetChange"
      @bet-set="onBetSet"
      @primary="onPrimary"
      @cancel="onCancel"
    />

    <Transition name="toast">
      <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
    </Transition>

    <WinnerOverlay
      v-if="winnerVisible && winnerSpin"
      :outcome="winnerOutcome"
      :winner-username="winnerSpin.winnerUsername"
      :winner-profile-pic="winnerSpin.winnerProfilePic"
      :pot="formatAmount(fromMinor(winnerSpin.pot, TON), TON)"
      :winning-number="winnerSpin.winningNumber"
      @dismiss="winnerVisible = false"
    />
    <TutorialOverlay v-if="tutorialOpen" @dismiss="onTutorialDismiss" />
    <FairnessModal
      v-if="fairnessOpen"
      :server-seed-hash="serverSeedHash"
      :round-nonce="roundNonce"
      :last-spin="lastSpin"
      @close="fairnessOpen = false"
    />
  </div>
</template>

<style scoped>
.screen {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100dvh;
  overflow: hidden;
  /* A machined table pit with a warm overhead lamp. The wheel sits down inside
     it now instead of filling the screen edge to edge, so the arena reads as an
     object on a surface rather than a flat disc. The scrim keeps HUD contrast. */
  background:
    linear-gradient(180deg, rgba(11, 16, 23, 0.72) 0%, rgba(11, 16, 23, 0.1) 24%, rgba(11, 16, 23, 0.55) 100%),
    var(--c-bg) url('/env.webp') center / cover no-repeat;
}

.screen.impact {
  animation: screen-shake 0.42s cubic-bezier(0.36, 0.07, 0.19, 0.97);
}

@media (prefers-reduced-motion: reduce) {
  .screen.impact {
    animation: none;
  }
}

.help {
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

@media (prefers-reduced-transparency: reduce) {
  .help {
    background: var(--c-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

.toast {
  position: absolute;
  left: 50%;
  bottom: 150px;
  transform: translateX(-50%);
  z-index: 25;
  max-width: 88%;
  padding: 11px 16px;
  border-radius: var(--r-chip);
  background: var(--c-surface-2);
  border: 1px solid var(--c-line-2);
  box-shadow: var(--sh-2);
  color: var(--c-text);
  font-size: 13px;
  font-weight: 600;
  text-align: center;
  white-space: nowrap;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity var(--d-pop) var(--e-out);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .toast-enter-active,
  .toast-leave-active {
    transition: none;
  }
}
</style>
