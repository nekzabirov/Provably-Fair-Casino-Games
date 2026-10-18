<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PhQuestion, PhShieldCheck } from '@phosphor-icons/vue'
import GameHud from './GameHud.vue'
import PlinkoBoard from './PlinkoBoard.vue'
import ControlsPanel from './ControlsPanel.vue'
import ErrorScreen from './ErrorScreen.vue'
import BigWinOverlay from './BigWinOverlay.vue'
import TutorialOverlay from './TutorialOverlay.vue'
import FairnessModal from './FairnessModal.vue'
import MuteButton from './MuteButton.vue'
import TonIcon from './TonIcon.vue'
import { currencyRule, formatAmount, formatPayout, fromMinor, roundToStep, toMinor } from '../currency'
import { requestDeposit } from '../config'
import { usePlinko } from '../composables/usePlinko'
import { MAX_ROWS, MIN_ROWS, type Risk } from '../tables'
import { isMuted, playDrop, setMuted, unlockAudio } from '../sound'
import { hasSeenTutorial, markTutorialSeen } from '../onboarding'
import { forceHideSplash, markAuthReady } from '../preload'
import { loadBet, loadRisk, loadRows, saveBet, saveRisk, saveRows } from '../prefs'
import type { Fairness, PlinkoState } from '../net/protocol'

const {
  state,
  connected,
  error,
  fatalError,
  currency,
  clientSeed,
  pending,
  setClientSeed,
  onResult,
  drop,
} = usePlinko()

// App.vue only mounts this for a supported currency, so the rule is always present.
const rule = computed(() => currencyRule(currency.value)!)
const presets = computed(() => rule.value.presets)

// Player choices for the next drop, hydrated from the last session.
const bet = ref(loadBet(currency.value, rule.value.min))
const rows = ref(loadRows(12))
const risk = ref<Risk>(loadRisk('MEDIUM'))

const board = ref<InstanceType<typeof PlinkoBoard> | null>(null)
const ballsFlying = ref(0)

/** Rows/risk rebuild the board, so they wait until the air is clear. */
const paramsLocked = computed(() => ballsFlying.value > 0 || pending.value > 0)

// ── Balance: optimistic local ledger ─────────────────────────────────────────
// The server balance in a RESULT frame already includes the payout, but showing
// it before the ball lands would spoil the outcome. So the display debits at
// drop time, credits when the ball lands, and hard-syncs whenever nothing is in
// flight (auth, reconnect, error refunds).
const displayedBalance = ref<number | null>(null)
const inflightStakes: number[] = []

const balanceDisplay = computed(() => {
  const b = displayedBalance.value
  return b == null ? '—' : formatAmount(fromMinor(b, rule.value), rule.value)
})

watch(state, (s) => {
  if (!s) return
  if (s.state === 'WAIT') {
    // Auth/reconnect snapshot — authoritative.
    displayedBalance.value = s.player.balance
    inflightStakes.length = 0
  }
})

// ── Drop results → balls ──────────────────────────────────────────────────────
const lastFairness = ref<Fairness | null>(null)
const lastRows = ref(0)
const lastRisk = ref<Risk | null>(null)

// Result pill above the panel: outcome of the last landed ball.
const lastResult = ref<{ text: string; win: boolean } | null>(null)
// `text` shows "×mult  ±delta"; the TonIcon is appended in the template.
let pillTimer = 0

// Big-win celebration (≥ ×10).
const winVisible = ref(false)
const winAmount = ref('')
const winMult = ref('')

let unsubscribe: (() => void) | null = null

function handleResult(s: PlinkoState) {
  inflightStakes.shift()
  lastFairness.value = s.fairness
  lastRows.value = s.rows
  lastRisk.value = s.risk as Risk
  ballsFlying.value++
  board.value?.drop(s.path, s.slot, s.multiplier, () => {
    ballsFlying.value = Math.max(0, ballsFlying.value - 1)
    if (displayedBalance.value != null) displayedBalance.value += s.settleAmount
    // Nothing left in the air — trust the server's number completely.
    if (ballsFlying.value === 0 && inflightStakes.length === 0) {
      displayedBalance.value = s.player.balance
    }
    showPill(s)
    if (s.multiplier >= 10) {
      winAmount.value = formatPayout(s.settleAmount, rule.value)
      winMult.value = `×${s.multiplier}`
      winVisible.value = true
    }
  })
}

function showPill(s: PlinkoState) {
  const win = s.settleAmount >= s.placeAmount
  const delta = s.settleAmount - s.placeAmount
  const sign = delta >= 0 ? '+' : '−'
  lastResult.value = {
    text: `×${s.multiplier}  ${sign}${formatPayout(Math.abs(delta), rule.value)}`,
    win,
  }
  window.clearTimeout(pillTimer)
  pillTimer = window.setTimeout(() => (lastResult.value = null), 2400)
}

// ── Toast (insufficient balance etc.) ─────────────────────────────────────────
const toastMsg = ref<string | null>(null)
let toastTimer = 0

function showToast(msg: string) {
  toastMsg.value = msg
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => (toastMsg.value = null), 2600)
}

// ── Actions ──────────────────────────────────────────────────────────────────
// The button never locks while balls fly: tap as fast as you like, or hold it
// down to auto-drop until released or the balance runs out. The in-flight cap
// only guards the server from an unbounded unanswered queue.
const MAX_BURST = 8
const HOLD_REPEAT_MS = 220

const primaryDisabled = computed(() => !connected.value)

let holdTimer = 0

function tryDrop(): boolean {
  if (!connected.value || pending.value >= MAX_BURST) return true
  const minor = toMinor(bet.value, rule.value)
  if (displayedBalance.value != null && minor > displayedBalance.value) {
    showToast('Not enough balance — top up to play')
    requestDeposit()
    return false
  }
  playDrop()
  if (displayedBalance.value != null) displayedBalance.value -= minor
  inflightStakes.push(minor)
  drop(minor, rows.value, risk.value)
  saveBet(currency.value, bet.value)
  return true
}

function onPrimaryDown() {
  unlockAudio()
  onPrimaryUp()
  if (!tryDrop()) return
  holdTimer = window.setInterval(() => {
    if (!tryDrop()) onPrimaryUp()
  }, HOLD_REPEAT_MS)
}

function onPrimaryUp() {
  window.clearInterval(holdTimer)
  holdTimer = 0
}

function onBetChange(delta: number) {
  bet.value = Math.max(rule.value.min, roundToStep(bet.value + delta * rule.value.step, rule.value))
}

function onBetSet(value: number) {
  bet.value = Math.max(rule.value.min, roundToStep(value, rule.value))
}

function onRowsChange(delta: number) {
  if (paramsLocked.value) return
  rows.value = Math.min(MAX_ROWS, Math.max(MIN_ROWS, rows.value + delta))
  saveRows(rows.value)
}

function onRiskSet(value: Risk) {
  if (paramsLocked.value) return
  risk.value = value
  saveRisk(value)
}

// ── Error refunds ─────────────────────────────────────────────────────────────
// A rejected drop (operator refused the debit, validation error) never produces a
// RESULT, so its optimistic deduction is restored here and the player is told.
watch(error, (e) => {
  if (!e) return
  onPrimaryUp()
  const stake = inflightStakes.shift()
  if (stake != null && displayedBalance.value != null) displayedBalance.value += stake
  showToast(e)
})

// ── Splash / audio / tutorial ─────────────────────────────────────────────────
let authMarked = false
watch(state, (s) => {
  if (s && !authMarked) {
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

// ── Provably-fair modal ───────────────────────────────────────────────────────
const fairnessOpen = ref(false)

function onApplySeed(seed: string) {
  setClientSeed(seed)
  fairnessOpen.value = false
}

const modalFairness = computed(() => lastFairness.value ?? state.value?.fairness ?? null)

onMounted(() => {
  window.addEventListener('pointerdown', unlockOnce)
  tutorialOpen.value = !hasSeenTutorial()
  unsubscribe = onResult(handleResult)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', unlockOnce)
  window.clearTimeout(toastTimer)
  window.clearTimeout(pillTimer)
  onPrimaryUp()
  unsubscribe?.()
})
</script>

<template>
  <ErrorScreen v-if="fatalError" title="Session error" :detail="fatalError" />
  <div v-else class="screen">
    <GameHud :balance="balanceDisplay">
      <template #actions>
        <button class="icon-btn press" type="button" aria-label="How to play" @click="onHelp">
          <PhQuestion :size="20" weight="bold" />
        </button>
        <button
          class="icon-btn press"
          type="button"
          aria-label="Provably fair"
          @click="fairnessOpen = true"
        >
          <PhShieldCheck :size="20" weight="bold" />
        </button>
        <MuteButton :muted="muted" @toggle="onToggleMute" />
      </template>
    </GameHud>

    <div class="board-wrap">
      <PlinkoBoard ref="board" :rows="rows" :risk="risk" />

      <Transition name="pill">
        <div v-if="lastResult" class="pill" :class="{ win: lastResult.win, lose: !lastResult.win }">
          {{ lastResult.text }}&nbsp;<TonIcon :size="14" />
        </div>
      </Transition>

      <Transition name="toast">
        <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
      </Transition>
    </div>

    <ControlsPanel
      :bet="formatAmount(bet, rule)"
      :presets="presets"
      :rows="rows"
      :risk="risk"
      :bet-disabled="!connected"
      :params-disabled="paramsLocked"
      :primary-disabled="primaryDisabled"
      @bet-change="onBetChange"
      @bet-set="onBetSet"
      @rows-change="onRowsChange"
      @risk-set="onRiskSet"
      @primary-down="onPrimaryDown"
      @primary-up="onPrimaryUp"
    />

    <BigWinOverlay
      v-if="winVisible"
      :amount="winAmount"
      :multiplier="winMult"
      @dismiss="winVisible = false"
    />
    <TutorialOverlay v-if="tutorialOpen" @dismiss="onTutorialDismiss" />
    <FairnessModal
      v-if="fairnessOpen"
      :fairness="modalFairness"
      :rows="lastRows || rows"
      :risk="lastRisk"
      :client-seed="clientSeed"
      :locked="paramsLocked"
      @close="fairnessOpen = false"
      @apply-seed="onApplySeed"
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
  /* The cabinet artwork frames the board with real metal and a cool under-glow.
     The scrim keeps the HUD and ladder legible over the brightest part of the
     frame, and it never intrudes on the dark centre where the pegs live. */
  background:
    linear-gradient(180deg, rgba(11, 16, 23, 0.82) 0%, rgba(11, 16, 23, 0.18) 22%, rgba(11, 16, 23, 0.5) 100%),
    var(--c-bg) url('/env.webp') center / cover no-repeat;
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

@media (prefers-reduced-transparency: reduce) {
  .icon-btn {
    background: var(--c-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

.board-wrap {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
}

/* The result of the last landed ball. It sits at the top of the board rather
   than over the ladder, so it never covers the chip that just lit up. */
.pill {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 8;
  display: flex;
  align-items: center;
  padding: 9px 16px;
  border-radius: var(--r-chip);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.01em;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.pill.win {
  background: var(--c-win-soft);
  border: 1px solid var(--c-win);
  color: var(--c-win);
}

.pill.lose {
  background: var(--c-lose-soft);
  border: 1px solid var(--c-lose);
  color: var(--c-lose);
}

.pill-enter-active,
.pill-leave-active {
  transition:
    opacity var(--d-pop) var(--e-out),
    transform var(--d-pop) var(--e-out);
}

.pill-enter-from,
.pill-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-8px);
}

.toast {
  position: absolute;
  left: 50%;
  bottom: 16px;
  transform: translateX(-50%);
  z-index: 8;
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
  .pill-enter-active,
  .pill-leave-active,
  .toast-enter-active,
  .toast-leave-active {
    transition: none;
  }
}
</style>
