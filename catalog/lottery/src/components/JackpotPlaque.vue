<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { formatPoolDec } from '../format'

// The Pencil "Jackpot Main" plaque (node rwDSV), 1:1 with the current frame:
// a glossy black plate with a muted-gold stroke and glow, the pool figure in
// gold gradient with "Gram(TON)" sitting on its baseline. Nothing else.
const props = defineProps<{
  /** Prize pool, nano. */
  pool: number
}>()

// The pool only ever grows mid-round; roll the shown value up to it so the
// jackpot visibly climbs (state indication, not decoration). Never counts down.
const shown = ref(props.pool)
let raf = 0

function rollTo(target: number): void {
  cancelAnimationFrame(raf)
  const from = shown.value
  if (target <= from) {
    shown.value = target
    return
  }
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    shown.value = target
    return
  }
  const start = performance.now()
  const dur = 900
  const step = (t: number) => {
    const k = Math.min(1, (t - start) / dur)
    const e = 1 - Math.pow(1 - k, 3)
    shown.value = from + (target - from) * e
    if (k < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}

watch(
  () => props.pool,
  (p) => rollTo(p),
)

onUnmounted(() => cancelAnimationFrame(raf))
</script>

<template>
  <div class="jp">
    <span class="jp__amount gold-grad-text tnum">{{ formatPoolDec(shown) }}</span>
    <span class="jp__cur">Gram(TON)</span>
  </div>
</template>

<style scoped>
/* Node rwDSV: h88, r12, #2A2A2E→#0B0B0D, #B8923E 1.5px stroke, gold glow +
   a 1px white top sheen. */
.jp {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 3px;
  height: 88px;
  flex: none;
  padding: 0 14px;
  border: 1.5px solid #b8923e;
  border-radius: 12px;
  background: linear-gradient(180deg, #2a2a2e 0%, #0b0b0d 100%);
  box-shadow:
    0 0 20px rgba(201, 162, 75, 0.23),
    0 1px 1px rgba(255, 255, 255, 0.13);
}

/* Node xE9E5: 37px 800, gold gradient top→bottom. Clamped for narrow phones. */
.jp__amount {
  align-self: center;
  font-size: clamp(27px, 9.2vw, 37px);
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.01em;
  white-space: nowrap;
}

/* Node WRKXD: the currency word on the amount's baseline. */
.jp__cur {
  align-self: center;
  transform: translateY(9px);
  color: #c9a24b;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}
</style>
