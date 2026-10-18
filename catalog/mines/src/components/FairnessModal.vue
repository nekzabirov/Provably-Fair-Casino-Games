<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Fairness } from '../net/protocol'
import { computeHash, freshClientSeed, minePositions, normalizeSeed, sha256Hex } from '../fairness'

const props = defineProps<{
  fairness: Fairness | null
  mineCount: number
  clientSeed: string
  /** A round is in progress — the client seed is locked until it ends. */
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
  commitmentOk: boolean | null
  mines: number[]
  hash: string
}
const result = ref<Result | null>(null)
const verifying = ref(false)

const canVerify = computed(
  () => !!props.fairness?.serverSeed && props.fairness.tonSeed != null && props.fairness.nonce != null,
)

async function verify() {
  const f = props.fairness
  if (!f?.serverSeed || f.tonSeed == null || f.nonce == null) return
  verifying.value = true
  try {
    const hash = await computeHash(f.serverSeed, f.clientSeed, f.tonSeed, f.nonce)
    const mines = await minePositions(hash, props.mineCount)
    const recomputed = await sha256Hex(f.serverSeed)
    result.value = { commitmentOk: recomputed === f.serverSeedHash, mines, hash }
  } finally {
    verifying.value = false
  }
}

// Reset the verdict whenever the round (nonce) changes.
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
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
            <path
              d="M12 2 4 5v6c0 5 3.4 8.5 8 11 4.6-2.5 8-6 8-11V5l-8-3Z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linejoin="round"
            />
            <path d="m8.5 12 2.4 2.4L15.5 9.8" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          Provably Fair
        </div>
        <button class="x" type="button" aria-label="Close" @click="emit('close')">✕</button>
      </header>

      <div class="body">
        <p class="lead">
          Every board is fixed <strong>before</strong> you reveal a single tile, and you can prove it.
          The mine layout comes from a hash of three seeds - none of which any single party controls.
        </p>

        <ol class="how">
          <li><b>Server seed</b> - secret, ours. You get its <b>SHA-256 hash</b> up front (the sealed envelope).</li>
          <li><b>Client seed</b> - yours. Change it any time below; the next round uses it.</li>
          <li><b>TON seed</b> - a public TON block hash neither of us can predict.</li>
          <li>At round end we reveal the server seed. Recompute the hash and the board must match.</li>
        </ol>

        <div class="formula">
          hash = HMAC_SHA256(serverSeed, "clientSeed : tonSeed : nonce")
        </div>

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
            <button class="ghost" type="button" :disabled="locked" @click="regenerate">⟳</button>
          </div>
          <button
            class="apply"
            type="button"
            :disabled="locked || !seedDirty || !seedValid"
            @click="applySeed"
          >
            {{ locked ? 'Locked during a round' : 'Apply &amp; reconnect' }}
          </button>
        </section>

        <!-- Current round -->
        <section class="block">
          <div class="block__label">This round</div>
          <dl class="kv">
            <div class="row" @click="copy(fairness?.serverSeedHash)">
              <dt>Server seed (hash)</dt>
              <dd>{{ short(fairness?.serverSeedHash) || '-' }}</dd>
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
            <div class="row" @click="copy(fairness?.serverSeed)">
              <dt>Server seed</dt>
              <dd :class="{ sealed: !fairness?.serverSeed }">
                {{ fairness?.serverSeed ? short(fairness.serverSeed) : '🔒 revealed at round end' }}
              </dd>
            </div>
          </dl>
        </section>

        <!-- Verifier -->
        <section class="block">
          <div class="block__label">Verify</div>
          <button class="verify" type="button" :disabled="!canVerify || verifying" @click="verify">
            {{ verifying ? 'Verifying…' : canVerify ? 'Recompute this round' : 'Play a round to verify' }}
          </button>

          <div v-if="result" class="result">
            <div class="badge" :class="{ ok: result.commitmentOk, bad: result.commitmentOk === false }">
              {{ result.commitmentOk ? '✓ Commitment matches' : '✗ Commitment mismatch' }}
            </div>
            <div class="mines">
              <div class="mines__label">Mines were at cells</div>
              <div class="mines__list">
                <span v-for="m in result.mines" :key="m" class="cell">{{ m }}</span>
              </div>
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
  background: rgba(14, 22, 33, 0.82);
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
  border-radius: 22px 22px 0 0;
  box-shadow: 0 -16px 50px rgba(0, 0, 0, 0.55);
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
  border-radius: 22px 22px 0 0;
}
.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 700;
  color: var(--accent);
}
.x {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 10px;
  background: var(--tg-surface-2);
  color: var(--tg-hint);
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
  background: var(--tg-surface-2);
  border: 1px solid var(--tg-divider);
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
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
  background: var(--tg-surface-2);
  color: var(--tg-text);
  font-family: ui-monospace, Menlo, monospace;
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
  background: var(--tg-surface-2);
  color: var(--accent);
  font-size: 18px;
  cursor: pointer;
}
.apply,
.verify {
  height: 44px;
  border: none;
  border-radius: var(--tg-radius);
  background: var(--accent);
  color: var(--tg-text);
  font-size: 14px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.apply:disabled,
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
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12.5px;
  color: var(--tg-text);
  text-align: right;
  word-break: break-all;
}
.row dd.sealed {
  color: var(--tg-hint);
  font-family: inherit;
}
.result {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.badge {
  align-self: flex-start;
  padding: 7px 12px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
  background: var(--tg-surface-2);
  color: var(--tg-text);
}
.badge.ok {
  background: rgba(77, 205, 94, 0.16);
  color: var(--tg-win);
}
.badge.bad {
  background: rgba(236, 57, 66, 0.18);
  color: var(--tg-danger);
}
.mines__label {
  font-size: 12px;
  color: var(--tg-hint);
  margin-bottom: 6px;
}
.mines__list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.cell {
  min-width: 28px;
  padding: 4px 7px;
  border-radius: 8px;
  text-align: center;
  background: rgba(236, 57, 66, 0.16);
  color: var(--tg-danger);
  font-size: 12px;
  font-weight: 700;
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
