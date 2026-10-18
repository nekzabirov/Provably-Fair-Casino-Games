<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PlinkoScene } from '../game/PlinkoScene'
import { multipliers, type Risk } from '../tables'

const props = defineProps<{
  rows: number
  risk: Risk
}>()

const host = ref<HTMLDivElement | null>(null)
let scene: PlinkoScene | null = null
let resize: ResizeObserver | null = null

/** Drop one animated ball; onLand fires when it settles. Exposed to GameScreen. */
function drop(path: number[], slot: number, multiplier: number, onLand: () => void): void {
  scene?.dropBall({ path, slot, multiplier, onLand })
}

/** True while any ball is falling. */
function busy(): boolean {
  return scene?.busy ?? false
}

defineExpose({ drop, busy })

onMounted(async () => {
  const s = new PlinkoScene()
  await s.init(host.value!)
  scene = s
  scene.buildBoard(props.rows, multipliers(props.risk, props.rows))
  const fit = () => {
    if (!host.value || !scene) return
    scene.resize(host.value.clientWidth, host.value.clientHeight)
  }
  fit()
  resize = new ResizeObserver(fit)
  if (host.value) resize.observe(host.value)
})

// Rows or risk changed → rebuild the board (clears in-flight balls; the screen
// locks these controls while balls are falling, so nothing is lost in practice).
watch(
  () => [props.rows, props.risk] as const,
  ([rows, risk]) => scene?.buildBoard(rows, multipliers(risk, rows)),
)

onBeforeUnmount(() => {
  resize?.disconnect()
  scene?.destroy()
  scene = null
})
</script>

<template>
  <div ref="host" class="board" />
</template>

<style scoped>
/* The canvas is transparent; the well behind the pegs is painted here. A
   funnel-shaped highlight down the middle plus a darker rim gives the flat peg
   field the depth it was missing, at zero per-frame cost. */
.board {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  position: relative;
  line-height: 0;
  background:
    radial-gradient(58% 46% at 50% 4%, rgba(42, 159, 232, 0.16) 0%, rgba(42, 159, 232, 0) 70%),
    radial-gradient(120% 62% at 50% 96%, var(--c-surface) 0%, rgba(21, 30, 42, 0) 72%),
    linear-gradient(180deg, var(--c-bg-2) 0%, var(--c-bg) 62%);
  box-shadow: inset 0 1px 0 var(--c-line);
}

.board :deep(canvas) {
  display: block;
}
</style>
