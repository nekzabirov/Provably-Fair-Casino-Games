<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PhQuestion, PhShieldCheck } from '@phosphor-icons/vue'
import GameHud from './GameHud.vue'
import RoundStrip from './RoundStrip.vue'
import GameBoard from './GameBoard.vue'
import ControlsPanel from './ControlsPanel.vue'
import ErrorScreen from './ErrorScreen.vue'
import FailOverlay from './FailOverlay.vue'
import CashoutOverlay from './CashoutOverlay.vue'
import TutorialOverlay from './TutorialOverlay.vue'
import FairnessModal from './FairnessModal.vue'
import MinesSheet from './MinesSheet.vue'
import MuteButton from './MuteButton.vue'
import { currencyRule, formatAmount, formatPayout, fromMinor, roundToStep, toMinor } from '../currency'
import { requestDeposit } from '../config'
import { useMines } from '../composables/useMines'
import { isMuted, playCashout, playExplosion, playStart, setMuted, stopAmbient, unlockAudio } from '../sound'
import { multiplierAt, nextMultiplier } from '../multiplier'
import { hasSeenTutorial, markTutorialSeen } from '../onboarding'
import { forceHideSplash, markAuthReady } from '../preload'
import { loadBet, loadMineCount, saveBet, saveMineCount } from '../prefs'

const { state, connected, fatalError, currency, clientSeed, setClientSeed, start, open, cashout } =
  useMines()

// App.vue only mounts this for a supported currency, so the rule is always present.
const rule = computed(() => currencyRule(currency.value)!)
const presets = computed(() => rule.value.presets)

// Editable inputs for the NEXT round (used while there is no active round).
// Hydrated from the player's last session so they aren't re-asked.
const bet = ref(loadBet(currency.value, rule.value.min))
const mineCount = ref(loadMineCount(3))

const isActive = computed(() => state.value?.state === 'ACTIVE')
const showRepeat = computed(() => !isActive.value)

const betDisplay = computed(() =>
  isActive.value && state.value
    ? formatAmount(fromMinor(state.value.placeAmount, rule.value), rule.value)
    : formatAmount(bet.value, rule.value),
)
const minesDisplay = computed(() =>
  isActive.value && state.value ? state.value.mineCount : mineCount.value,
)
// For the verifier: the actual round's mine count (set on ACTIVE/FAIL/revealed WAIT),
// not the editable draft, so a finished round reproduces its real layout.
const fairnessMineCount = computed(() =>
  state.value?.fairness?.nonce != null ? state.value.mineCount : mineCount.value,
)

const balanceDisplay = computed(() => {
  const b = state.value?.player.balance
  return b == null ? '—' : formatAmount(fromMinor(b, rule.value), rule.value)
})

// ── Round multipliers (display only; the engine settles every amount) ────────
// `current` is whatever the engine reported for the last revealed cell, so it is
// never derived. `next` is a projection and hides itself if it cannot reproduce
// `current` — see multiplier.ts.
const openedCount = computed(() => state.value?.cells.length ?? 0)

const currentMultiplier = computed(() => {
  const cells = state.value?.cells
  return cells && cells.length > 0 ? cells[cells.length - 1].multiplier : null
})

const nextProjection = computed(() =>
  isActive.value
    ? nextMultiplier(state.value?.mineCount ?? mineCount.value, openedCount.value, currentMultiplier.value)
    : null,
)

/** What one safe pick pays at the drafted mine count — the number that actually
 *  moves when the player changes the mine count. */
const firstMultiplier = computed(() => multiplierAt(mineCount.value, 1))

const primaryVariant = computed<'bet' | 'cashout'>(() => (isActive.value ? 'cashout' : 'bet'))

const primaryLabel = computed(() => {
  const s = state.value
  if (s && s.state === 'ACTIVE') {
    return `Cashout ${formatPayout(s.settleAmount, rule.value)}`
  }
  return 'Play'
})

// The cash-out label ends in a payout amount, so append the TON icon after it.
const primaryShowCurrency = computed(() => isActive.value)

function onBetChange(delta: number) {
  if (isActive.value) return
  bet.value = Math.max(rule.value.min, roundToStep(bet.value + delta * rule.value.step, rule.value))
}

function onBetSet(value: number) {
  if (isActive.value) return
  bet.value = Math.max(rule.value.min, roundToStep(value, rule.value))
}

// ── Mines bottom sheet ──────────────────────────────────────────────────────
const minesSheetOpen = ref(false)

function onMinesSelect(n: number) {
  mineCount.value = Math.min(24, Math.max(1, n))
  saveMineCount(mineCount.value)
  minesSheetOpen.value = false
}

// ── Insufficient-balance toast ──────────────────────────────────────────────
const toastMsg = ref<string | null>(null)
let toastTimer = 0

function showToast(msg: string) {
  toastMsg.value = msg
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => (toastMsg.value = null), 2600)
}

/** Starts a round, guarding against an over-balance stake. Persists the choice. */
function tryStart(amount: number, mines: number) {
  const minor = toMinor(amount, rule.value)
  const balance = state.value?.player.balance
  if (balance != null && minor > balance) {
    showToast('Not enough balance — top up to play')
    requestDeposit()
    return
  }
  playStart()
  start(minor, mines)
  saveBet(currency.value, amount)
  saveMineCount(mines)
}

function onRepeat() {
  const amount = loadBet(currency.value, rule.value.min)
  const mines = loadMineCount(mineCount.value)
  bet.value = amount
  mineCount.value = mines
  tryStart(amount, mines)
}

// ── Board / overlay state ─────────────────────────────────────────────────────
const mineIndex = ref<number | null>(null)
const failVisible = ref(false)
const shaking = ref(false)
// After dismissing the blast, the board is wiped until the next round starts.
const cleared = ref(false)
let lastOpened: number | null = null

// Cash-out win: the WAIT frame is zeroed, so capture the payout at tap time and
// reveal the celebration only once the server confirms with the WAIT transition.
const winVisible = ref(false)
const winAmount = ref('')
const cashoutPending = ref(false)
const celebrate = ref(0)
let capturedSettle = 0
let capturedPlace = 0

const boardCells = computed(() => (cleared.value ? [] : (state.value?.cells ?? [])))
const boardMine = computed(() => (cleared.value ? null : mineIndex.value))

watch(
  () => state.value?.state,
  (s) => {
    cleared.value = false
    if (s === 'FAIL') {
      // The engine doesn't echo the fatal cell, so use the one we just opened.
      mineIndex.value = lastOpened
      failVisible.value = true
      cashoutPending.value = false
      playExplosion()
      shaking.value = true
      window.setTimeout(() => (shaking.value = false), 450)
      return
    }
    mineIndex.value = null
    failVisible.value = false
    if (s === 'WAIT' && cashoutPending.value) {
      cashoutPending.value = false
      if (capturedSettle > capturedPlace) {
        winAmount.value = formatPayout(capturedSettle, rule.value)
        winVisible.value = true
        celebrate.value++
      }
    }
  },
)

// ── Splash coordination (lives here, but force-hides on fatalError so it can't hang) ──
let authMarked = false
watch(
  () => state.value,
  (s) => {
    if (s && !authMarked) {
      authMarked = true
      markAuthReady()
    }
  },
)
watch(fatalError, (e) => {
  if (e) forceHideSplash()
})

// ── Audio ─────────────────────────────────────────────────────────────────────
const muted = ref(isMuted())

function onToggleMute() {
  muted.value = !muted.value
  setMuted(muted.value)
}

// Browser autoplay rules block audio until a user gesture; the first tap anywhere
// unlocks the context and starts the ambient bed.
function unlockOnce() {
  unlockAudio()
  window.removeEventListener('pointerdown', unlockOnce)
}

// ── Tutorial ───────────────────────────────────────────────────────────────────
const tutorialOpen = ref(false)

function onHelp() {
  unlockAudio()
  tutorialOpen.value = true
}

// ── Provably-fair modal ─────────────────────────────────────────────────────────
const fairnessOpen = ref(false)

function onApplySeed(seed: string) {
  setClientSeed(seed)
  fairnessOpen.value = false
}

function onTutorialDismiss() {
  markTutorialSeen()
  tutorialOpen.value = false
}

onMounted(() => {
  window.addEventListener('pointerdown', unlockOnce)
  tutorialOpen.value = !hasSeenTutorial()
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', unlockOnce)
  window.clearTimeout(toastTimer)
  stopAmbient()
})

// ── Actions ─────────────────────────────────────────────────────────────────────
function onPrimary() {
  if (isActive.value && state.value) {
    capturedSettle = state.value.settleAmount
    capturedPlace = state.value.placeAmount
    cashoutPending.value = true
    playCashout()
    cashout()
  } else {
    tryStart(bet.value, mineCount.value)
  }
}

function onCellOpen(index: number) {
  unlockAudio()
  lastOpened = index
  open(index)
}

function onFailDismiss() {
  failVisible.value = false
  cleared.value = true
}

function onWinDismiss() {
  winVisible.value = false
}
</script>

<template>
  <ErrorScreen v-if="fatalError" title="Session error" :detail="fatalError" />
  <div v-else class="screen" :class="{ shake: shaking }">
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

    <RoundStrip
      :active="isActive"
      :current="currentMultiplier"
      :next="nextProjection"
      :mine-count="minesDisplay"
      :first="firstMultiplier"
    />

    <div class="board-wrap">
      <GameBoard
        :cells="boardCells"
        :mine-index="boardMine"
        :interactive="isActive"
        :celebrate="celebrate"
        @open="onCellOpen"
      />
      <Transition name="toast">
        <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
      </Transition>
    </div>

    <ControlsPanel
      :bet="betDisplay"
      :presets="presets"
      :mine-count="minesDisplay"
      :primary-label="primaryLabel"
      :primary-show-currency="primaryShowCurrency"
      :primary-variant="primaryVariant"
      :inputs-disabled="isActive"
      :primary-disabled="!connected"
      :show-repeat="showRepeat"
      @bet-change="onBetChange"
      @bet-set="onBetSet"
      @open-mines="minesSheetOpen = true"
      @primary="onPrimary"
      @repeat="onRepeat"
    />

    <FailOverlay v-if="failVisible" @dismiss="onFailDismiss" />
    <CashoutOverlay v-if="winVisible" :amount="winAmount" @dismiss="onWinDismiss" />
    <TutorialOverlay v-if="tutorialOpen" @dismiss="onTutorialDismiss" />
    <FairnessModal
      v-if="fairnessOpen"
      :fairness="state?.fairness ?? null"
      :mine-count="fairnessMineCount"
      :client-seed="clientSeed"
      :locked="isActive"
      @close="fairnessOpen = false"
      @apply-seed="onApplySeed"
    />
    <MinesSheet
      v-if="minesSheetOpen"
      :value="mineCount"
      @select="onMinesSelect"
      @close="minesSheetOpen = false"
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
  /* The backdrop is deliberately near-black and defocused so the tiles are the
     brightest thing on screen. The scrim on top guarantees HUD contrast even if
     the artwork is ever swapped for a busier one. */
  background:
    linear-gradient(180deg, rgba(11, 16, 23, 0.92) 0%, rgba(11, 16, 23, 0.35) 26%, rgba(11, 16, 23, 0.6) 100%),
    var(--c-bg) url('/bg.webp') center / cover no-repeat;
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

.board-wrap {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
}

.toast {
  position: absolute;
  left: 50%;
  bottom: 16px;
  transform: translate(-50%, 0);
  z-index: 8;
  max-width: 88%;
  padding: 11px 18px;
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

/* Enters and leaves in the same direction it lives, so a dismissal reads as the
   toast going back where it came from rather than blinking out. */
.toast-enter-active,
.toast-leave-active {
  transition:
    opacity var(--d-pop) var(--e-out),
    transform var(--d-pop) var(--e-out);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px) scale(0.96);
}

.toast-enter-to,
.toast-leave-from {
  transform: translate(-50%, 0) scale(1);
}

.screen.shake {
  animation: screen-shake 0.45s ease-in-out;
}

/* Slight scale keeps the edges covered while the screen jolts. */
@keyframes screen-shake {
  0%,
  100% {
    transform: scale(1) translate(0, 0);
  }
  15% {
    transform: scale(1.03) translate(-7px, 4px);
  }
  35% {
    transform: scale(1.03) translate(7px, -4px);
  }
  55% {
    transform: scale(1.03) translate(-5px, -3px);
  }
  75% {
    transform: scale(1.03) translate(5px, 3px);
  }
  90% {
    transform: scale(1.02) translate(-2px, 1px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .screen.shake {
    animation: none;
  }
  .toast-enter-active,
  .toast-leave-active {
    transition: none;
  }
}
</style>
