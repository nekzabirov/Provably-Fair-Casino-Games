<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SpinFrame } from '../net/protocol'
import { computeHash, fraction, sha256Hex, winnerIndex, winningNumber } from '../fairness'
import { formatAmount, fromMinor, TON } from '../currency'
import { PhCheckCircle, PhShieldCheck, PhX, PhXCircle } from '@phosphor-icons/vue'
import TonIcon from './TonIcon.vue'

const props = defineProps<{
  /** Current round's commitment (shown while bets are open). */
  serverSeedHash: string
  roundNonce: number
  /** The most recent locked round, or null before the first spin. */
  lastSpin: SpinFrame | null
}>()

const emit = defineEmits<{ close: [] }>()

const result = ref<{ commitmentOk: boolean; number: number; winner: string } | null>(null)
const verifying = ref(false)
const canVerify = computed(() => !!props.lastSpin)

async function verify() {
  const s = props.lastSpin
  if (!s) return
  verifying.value = true
  try {
    const hash = await computeHash(s.serverSeed, s.clientSeed, s.roundNonce)
    const recomputed = await sha256Hex(s.serverSeed)
    const f = fraction(hash)
    const idx = winnerIndex(f, s.roster.map((seat) => seat.stake))
    result.value = {
      commitmentOk: recomputed === serverSeedHashForSpin.value,
      number: winningNumber(f),
      winner: s.roster[idx]?.username ?? '-',
    }
  } finally {
    verifying.value = false
  }
}

// The commitment to check against is the hash that was live *for that round*. We
// only retain the current round's hash, so the check is meaningful when the last
// spin is still the latest round; otherwise we just show the recomputed number.
const serverSeedHashForSpin = computed(() =>
  props.lastSpin && props.lastSpin.roundNonce === props.roundNonce ? props.serverSeedHash : '',
)

watch(
  () => props.lastSpin?.roundNonce,
  () => (result.value = null),
)

function short(s?: string | null): string {
  if (!s) return '-'
  return s.length > 20 ? `${s.slice(0, 10)}…${s.slice(-8)}` : s
}
function ton(minor?: number | null): string {
  return minor == null ? '-' : formatAmount(fromMinor(minor, TON), TON)
}
async function copy(text?: string | null) {
  if (text) {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      /* ignore */
    }
  }
}
</script>

<template>
  <div class="veil" @click.self="emit('close')">
    <div class="sheet">
      <header class="head">
        <div class="title">
          <PhShieldCheck :size="20" weight="bold" />
          Provably Fair
        </div>
        <button class="x press" type="button" aria-label="Close" @click="emit('close')">
          <PhX :size="18" weight="bold" />
        </button>
      </header>

      <div class="body">
        <p class="lead">
          The winning number is locked <strong>before</strong> the wheel spins, and you can prove it.
          Because one wheel is shared by everyone, the round mixes <strong>our secret seed</strong>
          with a <strong>public TON block hash</strong> - a value nobody can predict or pick.
        </p>

        <ol class="how">
          <li><b>Server seed</b> - secret, ours. Its <b>SHA-256 hash</b> is shown while bets are open.</li>
          <li><b>TON seed</b> - the hash of a TON block, public and unpredictable.</li>
          <li><b>Nonce</b> - the round number.</li>
          <li>On lock we reveal the server seed. Recompute → the same number → the same winning slice.</li>
        </ol>

        <div class="formula">number = 100 × ( HMAC_SHA256(serverSeed, tonSeed + nonce) → first 32 bits / 2³²)</div>

        <section class="block">
          <div class="block__label">Current round commitment</div>
          <dl class="kv">
            <div class="row" @click="copy(serverSeedHash)">
              <dt>Server seed (hash)</dt>
              <dd>{{ short(serverSeedHash) || '-' }}</dd>
            </div>
          </dl>
        </section>

        <section class="block">
          <div class="block__label">Last spin</div>
          <dl class="kv">
            <div class="row" @click="copy(lastSpin?.serverSeed)">
              <dt>Server seed</dt>
              <dd>{{ short(lastSpin?.serverSeed) }}</dd>
            </div>
            <div class="row" @click="copy(lastSpin?.clientSeed)">
              <dt>TON seed</dt>
              <dd>{{ short(lastSpin?.clientSeed) }}</dd>
            </div>
            <div class="row">
              <dt>Nonce (round)</dt>
              <dd>{{ lastSpin?.roundNonce ?? '-' }}</dd>
            </div>
            <div class="row">
              <dt>Number / winner</dt>
              <dd>{{ lastSpin ? `${Math.round(lastSpin.winningNumber)} · ${lastSpin.winnerUsername}` : '-' }}</dd>
            </div>
            <div class="row">
              <dt>Pot</dt>
              <dd>{{ ton(lastSpin?.pot) }} <TonIcon :size="14" /></dd>
            </div>
          </dl>
        </section>

        <section class="block">
          <div class="block__label">Verify</div>
          <button class="verify" type="button" :disabled="!canVerify || verifying" @click="verify">
            {{ verifying ? 'Verifying…' : canVerify ? 'Recompute last spin' : 'Wait for a spin to verify' }}
          </button>
          <div v-if="result" class="result">
            <div
              v-if="serverSeedHashForSpin"
              class="badge"
              :class="{ ok: result.commitmentOk, bad: !result.commitmentOk }"
            >
              <component :is="result.commitmentOk ? PhCheckCircle : PhXCircle" :size="16" weight="fill" />
              {{ result.commitmentOk ? 'Commitment matches' : 'Commitment mismatch' }}
            </div>
            <div class="badge ok">Number {{ Math.round(result.number) }} to {{ result.winner }}</div>
          </div>
        </section>

        <p class="foot">
          The math is open source. Paste the server seed, TON seed and nonce into any independent
          HMAC-SHA256 calculator to confirm the number yourself.
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.veil {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(7, 12, 19, 0.86);
  animation: veil 0.25s ease-out both;
}
@keyframes veil {
  from {
    opacity: 0;
  }
}
.sheet {
  width: 100%;
  max-width: 460px;
  max-height: 92dvh;
  display: flex;
  flex-direction: column;
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-bottom: none;
  border-radius: var(--r-sheet) var(--r-sheet) 0 0;
  box-shadow: var(--sh-2);
  animation: rise 0.32s cubic-bezier(0.2, 0.9, 0.3, 1) both;
}
@keyframes rise {
  from {
    transform: translateY(24px);
    opacity: 0;
  }
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px 12px;
  background: var(--c-bg-2);
  border-radius: var(--r-sheet) var(--r-sheet) 0 0;
  border-bottom: 1px solid var(--tg-divider);
}
.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 700;
  color: var(--c-text);
}
.x {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--tap);
  height: var(--tap);
  border: none;
  border-radius: var(--r-chip);
  background: var(--c-surface-2);
  color: var(--c-text-2);
  cursor: pointer;
}
.body {
  overflow-y: auto;
  padding: 16px 18px calc(20px + var(--safe-bottom));
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.lead {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--tg-text);
}
.lead strong {
  color: var(--accent);
}
.how {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: 13px;
  line-height: 1.45;
  color: var(--tg-hint);
}
.how b {
  color: var(--tg-text);
}
.formula {
  padding: 10px 12px;
  border-radius: var(--r-ctl);
  background: var(--c-surface-2);
  border: 1px solid var(--c-line);
  font-family: var(--f-mono);
  font-size: 11px;
  color: var(--arena-gold);
  word-break: break-word;
}
.block {
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.block__label {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--tg-hint);
}
.kv {
  margin: 0;
  display: flex;
  flex-direction: column;
  border-radius: var(--r-ctl);
  overflow: hidden;
  border: 1px solid var(--c-line);
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: var(--tg-surface-2);
  border-bottom: 1px solid var(--tg-divider);
  cursor: pointer;
}
.row:last-child {
  border-bottom: none;
}
.row dt {
  font-size: 12.5px;
  color: var(--tg-hint);
}
.row dd {
  margin: 0;
  font-family: var(--f-mono);
  font-size: 12.5px;
  color: var(--tg-text);
  text-align: right;
  word-break: break-all;
}
.verify {
  height: var(--tap);
  border: none;
  border-radius: var(--r-ctl);
  background: var(--arena-gold);
  color: var(--arena-ink);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}
.verify:not(:disabled):active {
  background: var(--arena-gold-press);
}
.verify:disabled {
  background: var(--c-surface-2);
  color: var(--c-text-3);
  cursor: default;
}
.result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.badge {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: var(--r-chip);
  font-size: 13px;
  font-weight: 600;
  background: var(--c-surface-2);
  color: var(--c-text);
}
.badge.ok {
  background: var(--c-win-soft);
  color: var(--c-win);
}
.badge.bad {
  background: var(--c-lose-soft);
  color: var(--c-lose);
}
.foot {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.45;
  color: var(--tg-hint);
}
@media (prefers-reduced-motion: reduce) {
  .veil,
  .sheet {
    animation: none;
  }
}
</style>
