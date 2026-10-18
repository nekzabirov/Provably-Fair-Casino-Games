<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { PhArrowClockwise, PhEraser, PhWarningCircle } from '@phosphor-icons/vue'
import JackpotPlaque from './components/JackpotPlaque.vue'
import TicketCard from './components/TicketCard.vue'
import WinOverlay from './components/WinOverlay.vue'
import { useLottery } from './composables/useLottery'
import { formatChance, formatCount, formatOdds, formatTimer, formatTonAmount } from './format'
import { playBuy, playClear, playComplete, playDeny, playPick, playUnpick } from './sound'

const NANO = 1_000_000_000
const POOL = 49

const { state, loading, fatal, error, buying, buy } = useLottery()

// System-ticket bounds, server-driven (6..36 by default).
const minPicks = computed(() => state.value?.minPicks ?? 6)
const maxPicks = computed(() => state.value?.maxPicks ?? 36)

const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  clock = setInterval(() => (now.value = Date.now()), 1000)
})
onUnmounted(() => {
  if (clock) clearInterval(clock)
})

// The player's in-progress selection while picking a fresh ticket.
const picks = ref<number[]>([])

const heldTicket = computed(() => state.value?.tickets[0] ?? null)

const mode = computed<'pick' | 'locked'>(() => {
  const s = state.value
  if (!s) return 'locked'
  return s.bettingOpen && !s.finished && !heldTicket.value ? 'pick' : 'locked'
})

const cardNumbers = computed(() =>
  mode.value === 'pick' ? picks.value : (heldTicket.value?.numbers ?? []),
)

const ready = computed(() => picks.value.length >= minPicks.value)

// C(n, k) — the combinations a system ticket covers. Small (≤ C(36,6) < 2M), exact.
function choose(n: number, k: number): number {
  if (k < 0 || k > n) return 0
  let r = 1
  for (let i = 1; i <= k; i++) r = (r * (n - k + i)) / i
  return r
}

/** Combinations the ticket on the card covers (0 until six numbers are chosen). */
const combos = computed(() => choose(cardNumbers.value.length, minPicks.value))

/** Jackpot probability of the card's ticket: combinations covered over C(49, 6). */
const chance = computed(() => combos.value / choose(POOL, minPicks.value))

/** Final ticket price in TON: base × (n − 5)², matching the engine's priceOf. */
const priceTon = computed(() => {
  const s = state.value
  if (!s || cardNumbers.value.length < minPicks.value) return 0
  const steps = cardNumbers.value.length - minPicks.value + 1
  return (s.ticketPrice / NANO) * steps * steps
})

const priceText = computed(() => formatTonAmount(priceTon.value))

/* The stat plaques sell the ticket: combinations as a "×N" multiplier (every extra
   number visibly multiplies what the same ticket covers), the chance as real-lotto
   odds ("1 in 15.1K") until it earns a percent worth bragging about (≥ 0.1%). */
const combosText = computed(() => (combos.value > 0 ? `×${formatCount(combos.value)}` : '—'))

const chanceText = computed(() => {
  if (combos.value <= 0) return '—'
  return chance.value >= 0.001 ? formatChance(chance.value) : formatOdds(chance.value)
})

/* The stats bars fill on a log scale against the biggest possible ticket, so
   early picks visibly move the needle instead of hugging zero. */
const statPct = computed(() => {
  if (combos.value <= 0) return 0
  const max = choose(maxPicks.value, minPicks.value)
  return Math.max(4, Math.round((Math.log(combos.value + 1) / Math.log(max + 1)) * 100))
})

function toggle(n: number): void {
  const i = picks.value.indexOf(n)
  if (i >= 0) {
    picks.value = picks.value.filter((x) => x !== n)
    playUnpick()
  } else if (picks.value.length < maxPicks.value) {
    picks.value = [...picks.value, n]
    playPick(picks.value.length)
    // The 6th number completes a playable ticket — worth its own fanfare.
    if (picks.value.length === minPicks.value) playComplete()
  } else {
    flash(`You can pick at most ${maxPicks.value} numbers`)
    playDeny()
  }
}

function clear(): void {
  if (picks.value.length) playClear()
  picks.value = []
}

async function onBuy(): Promise<void> {
  if (!ready.value || buying.value) return
  const ok = await buy([...picks.value])
  if (ok) {
    picks.value = []
    playBuy()
  } else {
    playDeny()
  }
}

// Flash toast (buy failures + the pick-limit hint).
const localError = ref<string | null>(null)
let flashTimer: ReturnType<typeof setTimeout> | undefined
function flash(msg: string): void {
  localError.value = msg
  if (flashTimer) clearTimeout(flashTimer)
  flashTimer = setTimeout(() => (localError.value = null), 2600)
}
const toastMsg = computed(() => error.value ?? localError.value)

// Win celebration: fire once, only on the transition into a won result.
const winPayout = ref<number | null>(null)
const shownWinKey = ref<string | null>(null)
watch(
  () => state.value,
  (s) => {
    if (!s || !s.finished) return
    const winner = s.tickets.find((t) => t.matched && (t.payout ?? 0) > 0)
    if (!winner) return
    const key = `${s.drawNonce}:${winner.id}`
    if (shownWinKey.value === key) return
    shownWinKey.value = key
    winPayout.value = winner.payout ?? 0
  },
  { deep: true },
)

// The timer-bar face: the countdown while selling; the phase while drawing.
const drawing = computed(() => !!state.value && !state.value.bettingOpen && !state.value.finished)

const timer = computed(() => {
  const s = state.value
  if (!s) return ''
  if (s.finished) return 'DRAW COMPLETE'
  if (!s.bettingOpen || s.endsAt <= now.value) return 'DRAWING'
  return formatTimer(s.endsAt, now.value)
})

const status = computed(() => {
  const s = state.value
  if (!s) return ''
  if (s.finished) return 'Draw complete'
  if (!s.bettingOpen) return 'Numbers are being drawn'
  return 'Your ticket is in the draw'
})

function retry(): void {
  window.location.reload()
}
</script>

<template>
  <div class="screen">
    <!-- Loading -->
    <div v-if="loading" class="center">
      <span class="loader" />
    </div>

    <!-- Fatal load error. -->
    <div v-else-if="fatal || !state" class="center state">
      <div class="state__ic"><PhWarningCircle :size="30" weight="fill" /></div>
      <div class="state__tx">
        <h1 class="state__t">Draw is out of reach</h1>
        <p class="state__s">{{ fatal ?? 'The lottery did not answer.' }}</p>
        <p class="state__s dim">Your tickets are safe. They live on the draw, not on this screen.</p>
      </div>
      <button class="retry press" type="button" @click="retry">
        <PhArrowClockwise :size="18" weight="bold" />
        Try again
      </button>
    </div>

    <!-- Loaded: the Pencil "Lottery 42" column — timer, jackpot plaque, ticket,
         the two stat plaques; the action row sticks to the bottom. -->
    <template v-else>
      <div class="col">
        <div class="timerbar" :class="{ 'timerbar--drawing': drawing }">
          <span class="tnum">{{ timer }}</span>
        </div>

        <JackpotPlaque :pool="state.prizePool" />

        <TicketCard
          :selected="cardNumbers"
          :winning="state.winningNumbers"
          :mode="mode"
          :finished="state.finished"
          :min-picks="minPicks"
          :payout="heldTicket?.payout ?? null"
          :matched="heldTicket?.matched ?? null"
          @toggle="toggle"
        />

        <!-- Node RVcAw: the two gold stat plaques for OUR system ticket. -->
        <div class="stats">
          <div class="stat">
            <span class="stat__lbl">COMBINATIONS COVERED</span>
            <span :key="combosText" class="stat__val stat__val--pop gold-grad-text tnum">{{ combosText }}</span>
            <span class="stat__bar"><span class="stat__fill" :style="{ width: `${statPct}%` }" /></span>
          </div>
          <div class="stat">
            <span class="stat__lbl">WIN CHANCE</span>
            <span :key="chanceText" class="stat__val stat__val--pop gold-grad-text tnum">{{ chanceText }}</span>
            <span class="stat__bar"><span class="stat__fill" :style="{ width: `${statPct}%` }" /></span>
          </div>
        </div>
      </div>

      <!-- Node ffJod / asbVH: CLEAR + the gold BUY TICKET pill. -->
      <div class="actions">
        <template v-if="mode === 'pick'">
          <button class="clear press" type="button" :disabled="!picks.length || buying" @click="clear">
            <PhEraser :size="19" weight="bold" />
            CLEAR
          </button>

          <button
            class="buypill"
            :class="{ 'buypill--lure': ready && !buying }"
            type="button"
            :disabled="!ready || buying"
            :aria-label="ready ? `Buy ticket for ${priceText} Gram` : `Pick at least ${minPicks} numbers`"
            @click="onBuy"
          >
            <span v-if="buying" class="mini-loader" />
            <template v-else>
              <span>BUY TICKET</span>
              <template v-if="ready">
                <span class="buypill__dot" aria-hidden="true" />
                <span class="tnum">{{ priceText }} Gram</span>
              </template>
              <template v-else>
                <span class="buypill__dot" aria-hidden="true" />
                <span class="tnum">{{ picks.length }}/{{ minPicks }}</span>
              </template>
            </template>
          </button>
        </template>

        <div v-else class="statusbar">
          <span v-if="drawing" class="statusbar__pip" aria-hidden="true" />
          {{ status }}
        </div>
      </div>
    </template>

    <Transition name="fade">
      <div v-if="toastMsg" class="toast">
        <PhWarningCircle :size="17" weight="fill" />
        <span>{{ toastMsg }}</span>
      </div>
    </Transition>

    <WinOverlay v-if="winPayout != null" :payout="winPayout" @dismiss="winPayout = null" />
  </div>
</template>

<style scoped>
.center {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
}

/* ── error state ──────────────────────────────────────────────────────────── */
.state {
  padding: 0 28px;
  text-align: center;
}

.state__ic {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 62px;
  height: 62px;
  border-radius: 14px;
  color: #dbb65b;
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(201, 162, 75, 0.4);
}

.state__tx {
  display: flex;
  flex-direction: column;
  gap: 7px;
  max-width: 30ch;
}

.state__t {
  margin: 0;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: #fff;
}

.state__s {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.45;
  color: #f0d0d0;
}

.state__s.dim {
  color: #c98a88;
}

.retry {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: var(--tap);
  padding: 0 22px;
  border: 1px solid rgba(201, 162, 75, 0.4);
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.25);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
}

/* ── the design column: padding 10/10/14/10, gap 8 (node bi8Au) ───────────── */
.col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: calc(10px + var(--safe-top)) 10px 0;
}

/* ── timer bar (node ArWfT) ───────────────────────────────────────────────── */
.timerbar {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 42px;
  flex: none;
  border-radius: 6px;
  background: #160303;
  color: #c99a9a;
  font-size: 23px;
  font-weight: 700;
  letter-spacing: 2px;
}

/* Drawing is a live state: the face turns gold and breathes once per second. */
.timerbar--drawing {
  color: #dbb65b;
  animation: timer-breathe 1.2s ease-in-out infinite;
}

@keyframes timer-breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

/* ── stat plaques (nodes iTh7A / c2NtOL) ──────────────────────────────────── */
.stats {
  display: flex;
  gap: 8px;
  padding-top: 2px;
}

.stat {
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: 9px 12px 10px;
  border: 1px solid rgba(201, 162, 75, 0.27);
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.22);
}

.stat__lbl {
  color: #e8c6c6;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.stat__val {
  font-size: 22px;
  font-weight: 800;
  line-height: 1.1;
  white-space: nowrap;
}

/* Each pick re-mounts the value (:key), so the new figure lands with one short
   pop — feedback that the extra number just multiplied the ticket. */
.stat__val--pop {
  animation: stat-pop 180ms cubic-bezier(0.23, 1, 0.32, 1);
}

@keyframes stat-pop {
  from {
    transform: scale(0.92);
    opacity: 0.4;
  }
}

/* Node pvxXr: the 5px gold meter under each value. */
.stat__bar {
  display: block;
  height: 5px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.12);
  overflow: hidden;
}

.stat__fill {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #dbb65b 0%, #fceebe 100%);
  transition: width 320ms cubic-bezier(0.23, 1, 0.32, 1);
}

/* ── action row (node asbVH), sticky at the bottom ────────────────────────── */
.actions {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 10px calc(14px + var(--safe-bottom));
  background: linear-gradient(180deg, rgba(74, 6, 6, 0) 0%, rgba(74, 6, 6, 0.85) 38%, #4a0606 100%);
}

/* Node nq0qI: the quiet glass CLEAR. */
.clear {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 108px;
  height: 56px;
  flex: none;
  border: 1.5px solid rgba(201, 162, 75, 0.6);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.07);
  color: #efd99a;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.5px;
  cursor: pointer;
}

.clear:disabled {
  opacity: 0.45;
  cursor: default;
}

/* Node v3fM3Z: the gold BUY TICKET pill. One line, always: the label and the
   price never wrap, the type scales down before the button would break. */
.buypill {
  position: relative;
  overflow: hidden;
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 56px;
  border: 1.5px solid #b8923e;
  border-radius: 14px;
  background: linear-gradient(180deg, #fceebe 0%, #dbb65b 100%);
  color: #3a2600;
  font-size: clamp(14px, 4.1vw, 18px);
  font-weight: 800;
  letter-spacing: 0.5px;
  white-space: nowrap;
  cursor: pointer;
  box-shadow: 0 3px 12px rgba(201, 162, 75, 0.33);
  transition:
    transform 160ms cubic-bezier(0.23, 1, 0.32, 1),
    box-shadow 160ms cubic-bezier(0.23, 1, 0.32, 1);
}

/* Ready to buy: the pill breathes a wider gold glow and a sheen sweeps across
   every ~3s — the one element on screen allowed to call for attention. */
.buypill--lure {
  animation: lure-glow 3s ease-in-out infinite;
}

.buypill--lure::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 46%;
  background: linear-gradient(105deg, transparent 0%, rgba(255, 255, 255, 0.6) 50%, transparent 100%);
  transform: translateX(-130%) skewX(-18deg);
  animation: lure-sheen 3s ease-in-out infinite;
  pointer-events: none;
}

@keyframes lure-glow {
  0%,
  100% {
    box-shadow: 0 3px 12px rgba(201, 162, 75, 0.33);
  }
  45% {
    box-shadow: 0 3px 24px rgba(244, 200, 66, 0.65);
  }
}

@keyframes lure-sheen {
  0%,
  55% {
    transform: translateX(-130%) skewX(-18deg);
  }
  90%,
  100% {
    transform: translateX(400%) skewX(-18deg);
  }
}

.buypill > span {
  white-space: nowrap;
}

.buypill__dot {
  width: 4px;
  height: 4px;
  flex: none;
  border-radius: 2px;
  background: #6a4a0a;
}

/* Press feedback: the pill physically takes the touch. */
.buypill:not(:disabled):active {
  transform: translateY(2px) scale(0.99);
  box-shadow: 0 1px 5px rgba(201, 162, 75, 0.3);
}

/* Not enough picks yet: the same pill, visibly asleep. */
.buypill:disabled {
  color: rgba(58, 38, 0, 0.45);
  background: linear-gradient(180deg, #c5b389 0%, #9a8354 100%);
  border-color: rgba(184, 146, 62, 0.5);
  box-shadow: none;
  cursor: default;
}

/* Locked: the draw status across the whole row, in the CLEAR plate's language. */
.statusbar {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 56px;
  border: 1.5px solid rgba(201, 162, 75, 0.6);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.07);
  color: #efd99a;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.statusbar__pip {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #dbb65b;
  box-shadow: 0 0 6px #dbb65b;
  animation: pip 1.1s ease-in-out infinite;
}

@keyframes pip {
  0%,
  100% {
    opacity: 0.4;
  }
  50% {
    opacity: 1;
  }
}

.mini-loader {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 3px solid rgba(58, 38, 0, 0.25);
  border-top-color: #3a2600;
  animation: spin 0.7s linear infinite;
}

/* ── toast ────────────────────────────────────────────────────────────────── */
.toast {
  position: fixed;
  left: 50%;
  bottom: 96px;
  z-index: 60;
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 86%;
  padding: 11px 15px;
  border-radius: 12px;
  border: 1px solid rgba(255, 210, 190, 0.26);
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(180deg, #8a0f0f 0%, #4a0606 100%);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
  transform: translateX(-50%);
}

.toast svg {
  flex: none;
  color: #dbb65b;
}

.fade-enter-active,
.fade-leave-active {
  transition:
    opacity var(--d-pop) var(--e-out),
    transform var(--d-pop) var(--e-out);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}

@media (prefers-reduced-motion: reduce) {
  .timerbar--drawing,
  .statusbar__pip,
  .stat__val--pop,
  .buypill--lure,
  .buypill--lure::after {
    animation: none;
  }

  .stat__fill {
    transition: none;
  }

  .fade-enter-from,
  .fade-leave-to {
    transform: translateX(-50%);
  }
}
</style>
