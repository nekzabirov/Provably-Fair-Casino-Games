<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  PhArrowsClockwise,
  PhCheckCircle,
  PhLock,
  PhShieldCheck,
  PhX,
  PhXCircle,
} from '@phosphor-icons/vue'
import type { Fairness } from '../net/protocol'
import { computeHash, freshClientSeed, normalizeSeed, sha256Hex, winningCup } from '../fairness'

const props = defineProps<{
  fairness: Fairness | null
  clientSeed: string
  /** A round is in progress - the client seed is locked until it ends. */
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
const result = ref<{ commitmentOk: boolean; cup: number } | null>(null)
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
    const recomputed = await sha256Hex(f.serverSeed)
    result.value = { commitmentOk: recomputed === f.serverSeedHash, cup: winningCup(hash) }
  } finally {
    verifying.value = false
  }
}

watch(
  () => props.fairness?.nonce,
  () => (result.value = null),
)

function short(s?: string | null): string {
  if (!s) return '-'
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
          <PhX :size="18" weight="bold" />
        </button>
      </header>

      <div class="body">
        <p class="lead">
          The cup hiding the ball is locked in <strong>before</strong> you pick, and you can prove it.
          The winning cup comes from a hash of three seeds - none of which any single party controls.
        </p>

        <ol class="how">
          <li><b>Server seed</b> - secret, ours. You get its <b>SHA-256 hash</b> up front (the sealed envelope).</li>
          <li><b>Client seed</b> - yours. Change it any time below; the next round uses it.</li>
          <li><b>TON seed</b> - a public TON block hash neither of us can predict.</li>
          <li>At round end we reveal the server seed. Recompute the hash and the cup must match.</li>
        </ol>

        <div class="formula">hash = HMAC_SHA256(serverSeed, "clientSeed : tonSeed : nonce")</div>

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
          <button class="apply" type="button" :disabled="locked || !seedDirty || !seedValid" @click="applySeed">
            {{ locked ? 'Locked during a round' : 'Apply &amp; reconnect' }}
          </button>
        </section>

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
                <PhLock v-if="!fairness?.serverSeed" :size="13" weight="fill" />
                {{ fairness?.serverSeed ? short(fairness.serverSeed) : 'revealed at round end' }}
              </dd>
            </div>
          </dl>
        </section>

        <section class="block">
          <div class="block__label">Verify</div>
          <button class="verify" type="button" :disabled="!canVerify || verifying" @click="verify">
            {{ verifying ? 'Verifying' : canVerify ? 'Recompute this round' : 'Play a round to verify' }}
          </button>
          <div v-if="result" class="result">
            <div class="badge" :class="{ ok: result.commitmentOk, bad: !result.commitmentOk }">
              <component :is="result.commitmentOk ? PhCheckCircle : PhXCircle" :size="15" weight="fill" />
              {{ result.commitmentOk ? 'Commitment matches' : 'Commitment mismatch' }}
            </div>
            <div class="cupline">Ball was under cup <b>{{ result.cup + 1 }}</b></div>
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
  background: rgba(10, 7, 5, 0.84);
  animation: veil var(--d-pop) var(--e-out) both;
  pointer-events: auto;
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
  animation: rise var(--d-sheet) var(--e-sheet) both;
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
  border-bottom: 1px solid var(--tg-divider);
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
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--tap);
  height: var(--tap);
  border: none;
  border-radius: var(--r-chip);
  background: var(--tg-surface-2);
  color: var(--tg-text);
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
  border-radius: var(--r-ctl);
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
  height: var(--tap);
  padding: 0 12px;
  border-radius: var(--r-ctl);
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
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--tap);
  height: var(--tap);
  border-radius: var(--r-ctl);
  border: 1px solid var(--tg-divider);
  background: var(--tg-bg-deep);
  color: var(--accent);
  cursor: pointer;
}
.apply,
.verify {
  height: var(--tap);
  border: none;
  border-radius: var(--r-ctl);
  background: var(--copper);
  color: var(--copper-ink);
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
  background: var(--tg-surface-2);
  color: var(--c-text-3);
  cursor: default;
}
.kv {
  margin: 0;
  display: flex;
  flex-direction: column;
  border-radius: var(--r-ctl);
  overflow: hidden;
  border: 1px solid var(--tg-divider);
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: var(--tap);
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
.row dd.sealed {
  display: flex;
  align-items: center;
  gap: 5px;
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
  display: flex;
  align-items: center;
  gap: 6px;
  border-radius: var(--r-chip);
  font-size: 13px;
  font-weight: 800;
}
.badge.ok {
  background: var(--c-win-soft);
  color: var(--tg-win);
}
.badge.bad {
  background: var(--c-lose-soft);
  color: var(--tg-danger);
}
.cupline {
  font-size: 14px;
  color: var(--tg-text);
}
.cupline b {
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
