<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import LottoBall from './LottoBall.vue'

const POOL = 49
const COLS = 7

const props = defineProps<{
  /** The player's numbers: in-progress picks (pick mode) or a held ticket. */
  selected: number[]
  /** Numbers the draw has pulled so far. */
  winning: number[]
  /** pick = tappable selection; locked = a held ticket / live draw (read-only). */
  mode: 'pick' | 'locked'
}>()

const emit = defineEmits<{ toggle: [n: number] }>()

const numbers = Array.from({ length: POOL }, (_, i) => i + 1)

const selectedSet = computed(() => new Set(props.selected))
const winningSet = computed(() => new Set(props.winning))

function variantOf(n: number): 'red' | 'gold' | 'white' | 'drawn' {
  const mine = selectedSet.value.has(n)
  const drawn = winningSet.value.has(n)
  if (mine && drawn) return 'gold' // a match: the prize colour
  if (mine) return 'red' // my pick
  if (drawn) return 'drawn' // pulled, but not mine
  return 'white'
}

function onTap(n: number): void {
  if (props.mode !== 'pick') return
  emit('toggle', n)
}

// The ball size is measured from the real grid width so the 7 columns fit any
// phone from 360 up without hard-coding a breakpoint.
const grid = ref<HTMLDivElement | null>(null)
const ballSize = ref(40)
let ro: ResizeObserver | null = null

function measure(): void {
  const el = grid.value
  if (!el) return
  const gap = 6
  // Tiles are sized to the width and packed tight, so the board reads as a real
  // lotto card rather than a calendar of isolated cells.
  const cellW = (el.clientWidth - gap * (COLS - 1)) / COLS
  ballSize.value = Math.max(30, Math.floor(cellW))
}

onMounted(() => {
  measure()
  ro = new ResizeObserver(measure)
  if (grid.value) ro.observe(grid.value)
})

onBeforeUnmount(() => ro?.disconnect())
</script>

<template>
  <div ref="grid" class="grid" :class="{ 'is-pick': mode === 'pick' }">
    <button
      v-for="n in numbers"
      :key="n"
      class="cell"
      type="button"
      :disabled="mode !== 'pick'"
      :aria-pressed="selectedSet.has(n)"
      :style="{ animationDelay: `${(((n - 1) % COLS) + Math.floor((n - 1) / COLS)) * 18}ms` }"
      @click="onTap(n)"
    >
      <Transition :name="variantOf(n) === 'gold' ? 'hit' : 'pop'">
        <LottoBall
          :key="variantOf(n)"
          :value="n"
          :variant="variantOf(n)"
          :size="ballSize"
          pad
        />
      </Transition>
    </button>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  width: 100%;
}

.cell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 1;
  padding: 0;
  border: none;
  background: none;
  cursor: default;
  -webkit-tap-highlight-color: transparent;
}

.grid.is-pick .cell {
  cursor: pointer;
}

/* First paint: the 49 tiles arrive as a diagonal wave (18ms per diagonal,
   ~230ms total) instead of snapping in as a wall. Runs once, on mount. */
.cell {
  animation: cell-in 240ms var(--e-out) both;
}

@keyframes cell-in {
  from {
    opacity: 0;
    transform: scale(0.82);
  }
}

.cell > :deep(*) {
  grid-area: 1 / 1;
}

/* The transition wrapper stacks entering + leaving balls in the same spot. */
.cell {
  display: grid;
  place-items: center;
}

/* Pick / deselect: a spring-ish pop. Overlapping enter+leave, so the tile and
   the ball cross-fade in place instead of one waiting for the other. */
.pop-enter-active {
  animation: pop-in 240ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

.pop-leave-active {
  animation: pop-out 150ms var(--e-out) forwards;
}

@keyframes pop-in {
  0% {
    transform: scale(0.4);
    opacity: 0;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

@keyframes pop-out {
  to {
    transform: scale(0.6);
    opacity: 0;
  }
}

/* Match: a bigger overshoot plus a gold flare ring, so a hit reads as an event,
   not just a colour change. */
.hit-enter-active {
  animation: hit-in 460ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

.hit-leave-active {
  animation: pop-out 150ms var(--e-out) forwards;
}

@keyframes hit-in {
  0% {
    transform: scale(0.5);
    opacity: 0;
    filter: brightness(2.2);
  }
  45% {
    transform: scale(1.28);
    filter: brightness(1.5) drop-shadow(0 0 12px rgba(247, 206, 85, 0.9));
  }
  100% {
    transform: scale(1);
    opacity: 1;
    filter: brightness(1) drop-shadow(0 0 0 rgba(247, 206, 85, 0));
  }
}

@media (prefers-reduced-motion: reduce) {
  .cell,
  .pop-enter-active,
  .pop-leave-active,
  .hit-enter-active,
  .hit-leave-active {
    animation: none;
  }
}
</style>
