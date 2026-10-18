<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PhQuestion, PhShieldCheck } from '@phosphor-icons/vue'
import { config, requestDeposit } from '../config'
import { currencyRule, formatAmount, formatPayout, fromMinor, roundToStep, toMinor } from '../currency'
import { ShellScene } from '../game/ShellScene'
import { ShellGame, type Phase } from '../game/ShellGame'
import { useShell } from '../composables/useShell'
import * as sound from '../sound'
import ControlsPanel from './ControlsPanel.vue'
import GameHud from './GameHud.vue'
import HelpSheet from './HelpSheet.vue'
import MuteButton from './MuteButton.vue'
import ResultOverlay from './ResultOverlay.vue'
import ErrorScreen from './ErrorScreen.vue'
import FairnessModal from './FairnessModal.vue'

// App.vue only mounts this for a supported currency, so the rule is present.
const rule = currencyRule(config.currency)!

const { state, connected, error, fatalError, clientSeed, setClientSeed, start, pick } = useShell()

const canvas = ref<HTMLCanvasElement | null>(null)
const phase = ref<Phase>('IDLE')
const won = ref<boolean | null>(null)
const bet = ref(rule.min)
const pending = ref(false) // a start was sent; waiting for the server's ACTIVE echo

let scene: ShellScene | null = null
let game: ShellGame | null = null
let startedThisSession = false

const idle = computed(() => phase.value === 'IDLE' || phase.value === 'RESULT')
const primaryActive = computed(() => idle.value && connected.value && !pending.value)
const slotsEnabled = computed(() => phase.value === 'READY')

const betDisplay = computed(() => formatAmount(bet.value, rule))
const balanceDisplay = computed(() => {
  const b = state.value?.player.balance
  return b == null ? '-' : formatAmount(fromMinor(b, rule), rule)
})

/** A correct cup pays a flat 2x, so the return is knowable before the pick. While
 *  a round runs the stake is the one the engine actually took, not the draft. */
const PAYOUT_MULTIPLIER = 2

const toWinDisplay = computed(() => {
  const s = state.value
  const stake = !idle.value && s ? fromMinor(s.placeAmount, rule) : bet.value
  return formatAmount(roundToStep(stake * PAYOUT_MULTIPLIER, rule), rule)
})

/** What the round actually returned; only read while the result overlay is up. */
const resultAmount = computed(() =>
  state.value ? formatPayout(state.value.settleAmount, rule) : '0',
)

const primaryLabel = computed(() => {
  if (idle.value && !connected.value) return 'Connecting'
  switch (phase.value) {
    case 'REVEAL':
      return 'Remember the ball'
    case 'SHUFFLE':
      return 'Watch the cups'
    case 'READY':
      return 'Pick a cup'
    case 'PICKED':
    case 'RESOLVE':
      return 'Lifting the cup'
    default:
      return 'Play'
  }
})

// Browser autoplay rules block audio until a user gesture; the first tap anywhere
// unlocks the context and starts the ambient loop.
function unlockOnce() {
  sound.unlockAudio()
  window.removeEventListener('pointerdown', unlockOnce)
}

onMounted(() => {
  const el = canvas.value!
  scene = new ShellScene(el)
  game = new ShellGame(scene)
  game.onPhase = (p, w) => {
    phase.value = p
    won.value = w
  }
  game.onPick = (slot) => pick(slot)
  scene.start()
  window.addEventListener('pointerdown', unlockOnce)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', unlockOnce)
  sound.stopAmbient()
  scene?.dispose()
  scene = null
  game = null
})

// The server's state is the single source of truth that drives the animation.
watch(
  () => state.value,
  (s) => {
    if (!s || !game) return
    pending.value = false
    switch (s.state) {
      case 'ACTIVE':
        if (startedThisSession) {
          startedThisSession = false
          void game.begin()
        } else {
          game.resume() // reconnected into an open round
        }
        break
      case 'WIN':
        game.resolve(true, s.winningCup)
        break
      case 'LOSE':
        game.resolve(false, s.winningCup)
        break
      // WAIT: idle; the game is already reset (or will be by the next begin()).
    }
  },
)

// A transient error (e.g. operator rejected the debit) clears the pending start.
watch(error, () => (pending.value = false))

function onPointerMove(e: PointerEvent) {
  if (!scene || !game || phase.value !== 'READY') return
  game.hover(scene.pickCup(e.clientX, e.clientY))
}

function onPointerLeave() {
  game?.hover(null)
}

function onPick(e: PointerEvent) {
  if (!scene || !game || phase.value !== 'READY') return
  const idx = scene.pickCup(e.clientX, e.clientY)
  if (idx !== null) game.pick(idx)
}

function onSlot(index: number) {
  game?.pickSlot(index)
}

function onBetChange(delta: number) {
  if (!idle.value) return
  const next = Math.max(rule.min, roundToStep(bet.value + delta * rule.step, rule))
  if (next === bet.value) return
  bet.value = next
  sound.playTick()
}

// ── Audio ───────────────────────────────────────────────────────────────────────
const muted = ref(sound.isMuted())

function onToggleMute() {
  muted.value = !muted.value
  sound.setMuted(muted.value)
}

// ── How to play ─────────────────────────────────────────────────────────────────
const helpOpen = ref(false)

function onPrimary() {
  if (!primaryActive.value) return
  const minor = toMinor(bet.value, rule)
  const balance = state.value?.player.balance
  if (balance != null && minor > balance) {
    requestDeposit()
    return
  }
  sound.unlockAudio()
  sound.playStart()
  pending.value = true
  startedThisSession = true
  start(minor)
}

// Tap the win/lose overlay → back to the bet screen (round already closed server-side).
function onDismiss() {
  game?.reset()
}

// ── Provably-fair modal ─────────────────────────────────────────────────────────
const fairnessOpen = ref(false)
const roundActive = computed(() => state.value?.state === 'ACTIVE')

function onApplySeed(seed: string) {
  setClientSeed(seed)
  fairnessOpen.value = false
}
</script>

<template>
  <ErrorScreen v-if="fatalError" title="Session error" :detail="fatalError" />
  <div v-else class="screen">
    <canvas
      ref="canvas"
      class="stage"
      @pointermove="onPointerMove"
      @pointerleave="onPointerLeave"
      @click="onPick"
    />

    <!-- Scrim: the chrome sits over a lit render, so top-of-screen contrast is
         not guaranteed by any page background. This darkens only the bands the
         HUD occupies and leaves the dealer and the cups untouched. -->
    <div class="scrim" aria-hidden="true" />

    <div class="chrome">
      <GameHud :balance="balanceDisplay">
        <template #actions>
          <button class="act press" type="button" aria-label="How to play" @click="helpOpen = true">
            <PhQuestion :size="20" weight="bold" />
          </button>
          <button
            class="act press"
            type="button"
            aria-label="Provably fair"
            @click="fairnessOpen = true"
          >
            <PhShieldCheck :size="20" weight="bold" />
          </button>
          <MuteButton :muted="muted" @toggle="onToggleMute" />
        </template>
      </GameHud>

      <div class="spacer" />

      <ControlsPanel
        :bet="betDisplay"
        :to-win="toWinDisplay"
        :show-bet="idle"
        :bet-disabled="!idle"
        :slots-enabled="slotsEnabled"
        :primary-label="primaryLabel"
        :primary-active="primaryActive"
        @bet-change="onBetChange"
        @slot="onSlot"
        @primary="onPrimary"
      />
    </div>

    <ResultOverlay
      v-if="phase === 'RESULT'"
      :won="won"
      :amount="resultAmount"
      @dismiss="onDismiss"
    />
    <HelpSheet v-if="helpOpen" @close="helpOpen = false" />
    <FairnessModal
      v-if="fairnessOpen"
      :fairness="state?.fairness ?? null"
      :client-seed="clientSeed"
      :locked="roundActive"
      @close="fairnessOpen = false"
      @apply-seed="onApplySeed"
    />
  </div>
</template>

<style scoped>
.screen {
  position: relative;
  width: 100%;
  height: 100dvh;
  overflow: hidden;
  background: var(--warm-bg);
}

.stage {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  touch-action: none;
}

/* Only the top band needs help: the control panel below is opaque and the middle
   of the screen is the scene itself, which must stay untouched. */
.scrim {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 190px;
  background: linear-gradient(180deg, rgba(10, 7, 5, 0.62) 0%, rgba(10, 7, 5, 0) 100%);
  pointer-events: none;
}

/* The chrome floats over the canvas; only its interactive children and the solid
   panel catch pointers, so cup taps still reach the 3D stage underneath. */
.chrome {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  pointer-events: none;
}

.chrome :deep(button),
.chrome :deep(.panel) {
  pointer-events: auto;
}

.spacer {
  flex: 1;
}

.act {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--tap);
  height: var(--tap);
  padding: 0;
  border-radius: var(--r-chip);
  border: 1px solid var(--c-line-2);
  background: var(--warm-veil);
  box-shadow: var(--sh-1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: var(--c-text-2);
  cursor: pointer;
}

@media (prefers-reduced-transparency: reduce) {
  .act {
    background: var(--warm-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
