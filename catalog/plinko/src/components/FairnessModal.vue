<script setup lang="ts">
import { PhArrowsClockwise, PhShieldCheck, PhX } from '@phosphor-icons/vue'
import { computed, ref, watch } from 'vue'
import type { Fairness } from '../net/protocol'
import { computeHash, freshClientSeed, normalizeSeed, pathBits, sha256Hex, slotOf } from '../fairness'
import { multiplierFor, type Risk } from '../tables'

const props = defineProps<{
  /** Fairness block of the latest resolved drop (or the pending one after auth). */
  fairness: Fairness | null
  /** Board parameters of the latest resolved drop — needed to decode the hash. */
  rows: number
  risk: Risk | null
  clientSeed: string
  /** Balls in flight — seed changes wait until the air clears. */
  locked: boolean
}>()

const emit = defineEmits<{ close: []; applySeed: [seed: string] }>()

// ── Editable client seed ────────────────────────────────────────────────────
const seedInput = ref(props.clientSeed)
watch(
  () => props.clientSeed,
  (s) => (seedInput.value = s),
)
const seedDirty = computed(() => normalizeSeed(seedInput.value) !== props.clientSeed)
const seedValid = computed(() => normalizeSeed(seedInput.value) !== '')

function applySeed() {
  const seed = normalizeSeed(seedInput.value)
  if (seed) emit('applySeed', seed)
}
function regenerate() {
  seedInput.value = freshClientSeed()
}

// ── Verifier ──────────────────────────────────────────────────────────────────
type Result = {
  commitmentOk: boolean
  path: number[]
  slot: number
  multiplier: number
}
const result = ref<Result | null>(null)
const verifying = ref(false)

const canVerify = computed(
  () =>
    !!props.fairness?.serverSeed &&
    props.fairness.tonSeed != null &&
    props.fairness.nonce != null &&
    props.risk != null &&
    props.rows > 0,
)

async function verify() {
  const f = props.fairness
  if (!f?.serverSeed || f.tonSeed == null || f.nonce == null || !props.risk) return
  verifying.value = true
  try {
    const hash = await computeHash(f.serverSeed, f.clientSeed, f.tonSeed, f.nonce)
    const recomputed = await sha256Hex(f.serverSeed)
    const slot = slotOf(hash, props.rows)
    result.value = {
      commitmentOk: recomputed === f.serverSeedHash,
      path: pathBits(hash, props.rows),
      slot,
      multiplier: multiplierFor(props.risk, props.rows, slot),
    }
  } finally {
    verifying.value = false
  }
}

// Reset the verdict whenever the drop (nonce) changes.
watch(
  () => props.fairness?.nonce,
  () => (result.value = null),
)

function short(s?: string | null): string {
  if (!s) return '—'
  return s.length > 20 ? `${s.slice(0, 10)}…${s.slice(-8)}` : s
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
          <PhShieldCheck :size="20" weight="fill" />
          Provably Fair
        </div>
        <button class="x press" type="button" aria-label="Close" @click="emit('close')">
          <PhX :size="16" weight="bold" />
        </button>
      </header>

      <div class="body">
        <p class="lead">
          Every ball's path is fixed <strong>before</strong> it falls, and you can prove it. The
          bounces come from a hash of three seeds - none of which any single party controls.
        </p>

        <ol class="how">
          <li><b>Server seed</b> - secret, ours. Its <b>SHA-256 hash</b> arrives with the result and seals the seed.</li>
          <li><b>Client seed</b> - yours. Change it any time below; the next drop uses it.</li>
          <li><b>TON seed</b> - a public TON block hash neither of us can predict.</li>
          <li>Each hex digit of the hash drives one row: even = left, odd = right.</li>
        </ol>

        <div class="formula">hash = HMAC_SHA256(serverSeed, "clientSeed : tonSeed : nonce")</div>

        <!-- Client seed editor -->
        <section class="block">
          <div class="block__label">Your client seed</div>
          <div class="seedrow">
            <input
              v-model="seedInput"
              class="seedinput"
              spellcheck="false"
              autocapitalize="off"
              :disabled="locked"
              maxlength="64"
            />
            <button class="ghost press" type="button" aria-label="New client seed" :disabled="locked" @click="regenerate">
              <PhArrowsClockwise :size="18" weight="bold" />
            </button>
          </div>
          <button
            class="apply press"
            type="button"
            :disabled="locked || !seedDirty || !seedValid"
            @click="applySeed"
          >
            {{ locked ? 'Wait for the balls to land' : 'Apply &amp; reconnect' }}
          </button>
        </section>

        <!-- Last drop -->
        <section class="block">
          <div class="block__label">Last drop</div>
          <dl class="kv">
            <div class="row" @click="copy(fairness?.serverSeedHash)">
              <dt>Server seed (hash)</dt>
              <dd>{{ short(fairness?.serverSeedHash) || '-' }}</dd>
            </div>
            <div class="row" @click="copy(fairness?.serverSeed)">
              <dt>Server seed</dt>
              <dd>{{ short(fairness?.serverSeed) }}</dd>
            </div>
            <div class="row" @click="copy(fairness?.clientSeed)">
              <dt>Client seed</dt>
              <dd>{{ short(fairness?.clientSeed) }}</dd>
            </div>
            <div class="row" @click="copy(fairness?.tonSeed)">
              <dt>TON seed</dt>
              <dd>{{ short(fairness?.tonSeed) }}</dd>
            </div>
            <div class="row">
              <dt>Nonce (round)</dt>
              <dd>{{ fairness?.nonce ?? '-' }}</dd>
            </div>
          </dl>
        </section>

        <!-- Verifier -->
        <section class="block">
          <div class="block__label">Verify</div>
          <button class="verify press" type="button" :disabled="!canVerify || verifying" @click="verify">
            {{ verifying ? 'Verifying…' : canVerify ? 'Recompute last drop' : 'Drop a ball to verify' }}
          </button>

          <div v-if="result" class="result">
            <div class="badge" :class="{ ok: result.commitmentOk, bad: !result.commitmentOk }">
              {{ result.commitmentOk ? '✓ Commitment matches' : '✗ Commitment mismatch' }}
            </div>
            <div class="pathline">
              <span v-for="(b, i) in result.path" :key="i" class="bit" :class="{ r: b === 1 }">
                {{ b === 1 ? 'R' : 'L' }}
              </span>
            </div>
            <div class="outcome">
              Slot <b>{{ result.slot }}</b> → <b>×{{ result.multiplier }}</b>
            </div>
          </div>
        </section>

        <p class="foot">
          The math is open source. Paste the server seed, client seed and nonce into any
          independent HMAC-SHA256 calculator to confirm the hash yourself.
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
  background: rgba(6, 11, 17, 0.84);
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
  border-radius: var(--r-sheet) var(--r-sheet) 0 0;
}
.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 800;
  color: var(--accent);
}
.x {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 10px;
  background: var(--tg-surface-2);
  color: var(--tg-text);
  font-size: 14px;
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
  border-radius: 10px;
  background: var(--tg-bg-deep);
  border: 1px solid var(--tg-divider);
  font-family: var(--f-mono);
  font-size: 11.5px;
  color: var(--accent);
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
.seedrow {
  display: flex;
  gap: 8px;
}
.seedinput {
  flex: 1 1 auto;
  min-width: 0;
  height: 42px;
  padding: 0 12px;
  border-radius: 11px;
  border: 1px solid var(--tg-divider);
  background: var(--tg-bg-deep);
  color: var(--tg-text);
  font-family: var(--f-mono);
  font-size: 13px;
}
.seedinput:disabled {
  opacity: 0.5;
}
.ghost {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  border: 1px solid var(--tg-divider);
  background: var(--tg-bg-deep);
  color: var(--accent);
  font-size: 18px;
  cursor: pointer;
}
.apply,
.verify {
  height: 44px;
  border: none;
  border-radius: var(--tg-radius);
  background: var(--c-brand);
  box-shadow: var(--sh-cta);
  color: #04121C;
  font-size: 14px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.apply:active,
.verify:active {
  background: var(--accent-press);
}
.apply:disabled,
.verify:disabled {
  background: var(--c-surface-2);
  color: var(--c-text-3);
  box-shadow: none;
  cursor: default;
}
.kv {
  margin: 0;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
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
  gap: 10px;
}
.badge {
  align-self: flex-start;
  padding: 7px 12px;
  border-radius: var(--r-chip);
  font-size: 13px;
  font-weight: 800;
  background: var(--tg-surface-2);
  color: var(--tg-text);
}
.badge.ok {
  background: var(--c-win-soft);
  color: var(--c-win);
}
.badge.bad {
  background: var(--c-lose-soft);
  color: var(--c-lose);
}
.pathline {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.bit {
  min-width: 22px;
  padding: 3px 0;
  border-radius: 6px;
  text-align: center;
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: 11px;
  font-weight: 800;
}
.bit.r {
  background: var(--c-gold-soft);
  color: var(--c-gold);
}
.outcome {
  font-size: 14px;
  color: var(--tg-text);
}
.outcome b {
  color: var(--accent);
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
