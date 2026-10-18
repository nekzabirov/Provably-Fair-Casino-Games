<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { PhCheckCircle, PhShieldCheck, PhX, PhXCircle } from '@phosphor-icons/vue'
import type { RevealedRound } from '../composables/useTeleFly'
import { computeHash, crashPoint, sha256Hex } from '../fairness'

const props = defineProps<{
  /** Commitment for the round currently in progress. */
  serverSeedHash: string
  /** The most recent crashed round's revealed seeds, or null before the first crash. */
  lastRound: RevealedRound | null
}>()

const emit = defineEmits<{ close: [] }>()

const result = ref<{ commitmentOk: boolean; crash: number } | null>(null)
const verifying = ref(false)
const canVerify = computed(() => !!props.lastRound)

async function verify() {
  const r = props.lastRound
  if (!r) return
  verifying.value = true
  try {
    const hash = await computeHash(r.serverSeed, r.clientSeed, r.roundNonce)
    const recomputed = await sha256Hex(r.serverSeed)
    result.value = { commitmentOk: recomputed === r.serverSeedHash, crash: crashPoint(hash) }
  } finally {
    verifying.value = false
  }
}

watch(
  () => props.lastRound?.roundNonce,
  () => (result.value = null),
)

function short(s?: string | null): string {
  if (!s) return '-'
  return s.length > 22 ? `${s.slice(0, 12)}…${s.slice(-8)}` : s
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
          The crash point is locked in <strong>before</strong> betting opens, and you can prove it
          after the round. Because one flight is shared by everyone, the round mixes
          <strong>our secret seed</strong> with a <strong>public TON block hash</strong> - a value
          nobody can predict or pick.
        </p>

        <ol class="how">
          <li><b>Server seed</b> - secret, ours. Its <b>SHA-256 hash</b> is broadcast while betting is open (the sealed envelope).</li>
          <li><b>TON seed</b> - the hash of a TON block, public and unpredictable. It stands in for a per-player seed in a shared round.</li>
          <li><b>Nonce</b> - the round number.</li>
          <li>On crash we reveal the server seed. Recompute and the crash point must match.</li>
        </ol>

        <div class="formula">crash = curve( HMAC_SHA256(serverSeed, tonSeed + nonce) )</div>

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
          <div class="block__label">Last crashed round</div>
          <dl class="kv">
            <div class="row" @click="copy(lastRound?.serverSeed)">
              <dt>Server seed</dt>
              <dd>{{ short(lastRound?.serverSeed) }}</dd>
            </div>
            <div class="row" @click="copy(lastRound?.clientSeed)">
              <dt>TON seed</dt>
              <dd>{{ short(lastRound?.clientSeed) }}</dd>
            </div>
            <div class="row">
              <dt>Nonce (round)</dt>
              <dd>{{ lastRound?.roundNonce ?? '-' }}</dd>
            </div>
            <div class="row">
              <dt>Crash point</dt>
              <dd>{{ lastRound ? `×${lastRound.crashMultiplier.toFixed(2)}` : '-' }}</dd>
            </div>
          </dl>
        </section>

        <section class="block">
          <div class="block__label">Verify</div>
          <button class="verify press" type="button" :disabled="!canVerify || verifying" @click="verify">
            {{ verifying ? 'Verifying…' : canVerify ? 'Recompute last round' : 'Wait for a crash to verify' }}
          </button>
          <div v-if="result && lastRound" class="result">
            <div class="badge" :class="{ ok: result.commitmentOk, bad: !result.commitmentOk }">
              <component :is="result.commitmentOk ? PhCheckCircle : PhXCircle" :size="16" weight="fill" />
              {{ result.commitmentOk ? 'Commitment matches' : 'Commitment mismatch' }}
            </div>
            <div
              class="badge"
              :class="{
                ok: Math.abs(result.crash - lastRound.crashMultiplier) < 0.01,
                bad: Math.abs(result.crash - lastRound.crashMultiplier) >= 0.01,
              }"
            >
              <component
                :is="Math.abs(result.crash - lastRound.crashMultiplier) < 0.01 ? PhCheckCircle : PhXCircle"
                :size="16"
                weight="fill"
              />
              <span class="tnum">
                Recomputed ×{{ result.crash.toFixed(2) }} vs shown ×{{ lastRound.crashMultiplier.toFixed(2) }}
              </span>
            </div>
          </div>
        </section>

        <p class="foot">
          The math is open source. Paste the server seed, TON seed and nonce into any independent
          HMAC-SHA256 calculator to confirm the hash yourself.
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
  background: rgba(14, 22, 33, 0.84);
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
  background: var(--tg-surface);
  border: 1px solid var(--tg-divider);
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
  background: var(--tg-header);
  border-bottom: 1px solid var(--tg-divider);
}
.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 700;
  color: var(--tg-link);
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
  padding: 16px 18px calc(20px + env(safe-area-inset-bottom));
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
  color: var(--tg-link);
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
  background: var(--tg-bg-deep);
  border: 1px solid var(--tg-divider);
  font-family: var(--f-mono);
  font-size: 11.5px;
  color: var(--tg-link);
  word-break: break-word;
}
.block {
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.block__label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--tg-hint);
}
.verify {
  height: 44px;
  border: none;
  border-radius: var(--tg-radius);
  background: var(--accent);
  color: #04121C;
  font-size: 14px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.verify:not(:disabled):active {
  background: var(--accent-press);
}
.verify:disabled {
  background: var(--tg-surface-2);
  color: var(--tg-hint);
  cursor: default;
}
.kv {
  margin: 0;
  display: flex;
  flex-direction: column;
  border-radius: var(--tg-radius);
  overflow: hidden;
  border: 1px solid var(--tg-divider);
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: var(--tg-bg-deep);
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
.result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.badge {
  align-self: flex-start;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 13px;
  border-radius: var(--r-chip);
  font-size: 13px;
  font-weight: 700;
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
