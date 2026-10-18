<script setup lang="ts">
// Replaces the old hero logo band. A phone in portrait has no room for
// decoration above the board, so this slot now carries the one thing the player
// could not previously see: what the round is currently worth, and what one
// more pick would pay.

defineProps<{
  /** Round is running (multipliers are live). */
  active: boolean
  /** Cumulative multiplier from the engine, or null before the first pick. */
  current: number | null
  /** Projected multiplier for one more safe pick, or null when unknown. */
  next: number | null
  /** Mines in play, shown while idle so the risk is visible before betting. */
  mineCount: number | string
  /**
   * What the first safe pick pays at the drafted mine count. Deliberately not
   * the full-clear maximum: with 3 mines that is ×2231, a number no player will
   * ever reach, and it would dominate the strip while telling them nothing
   * about the trade-off they are actually making.
   */
  first: number | null
}>()

function fmt(m: number): string {
  return `×${m.toFixed(2)}`
}
</script>

<template>
  <div class="strip" :class="{ 'is-active': active }">
    <template v-if="active">
      <div class="now">
        <span class="now__v tnum">{{ current == null ? '×1.00' : fmt(current) }}</span>
        <span class="now__l">now</span>
      </div>
      <div v-if="next != null" class="next">
        <span class="next__l">next pick</span>
        <span class="next__v tnum">{{ fmt(next) }}</span>
      </div>
    </template>

    <template v-else>
      <div class="idle">
        <span class="idle__k tnum">{{ mineCount }}</span>
        <span class="idle__l">mines</span>
      </div>
      <div v-if="first != null" class="idle idle--right">
        <span class="idle__l">first pick</span>
        <span class="idle__k tnum">{{ fmt(first) }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.strip {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 62px;
  margin: 4px var(--pad-x) 0;
  padding: 10px 16px;
  border-radius: var(--r-ctl);
  border: 1px solid var(--c-line);
  background: rgba(21, 30, 42, 0.72);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.strip.is-active {
  border-color: rgba(53, 208, 232, 0.32);
}

.now {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.now__v {
  font-size: 30px;
  font-weight: 700;
  line-height: 1;
  color: var(--gem);
  letter-spacing: -0.02em;
}

.now__l,
.next__l,
.idle__l {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-text-3);
}

.next {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
}

.next__v {
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
  color: var(--c-text);
}

.idle {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.idle--right {
  justify-content: flex-end;
}

.idle__k {
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
  color: var(--c-text);
}

.idle--right .idle__k {
  color: var(--gem);
}

@media (prefers-reduced-transparency: reduce) {
  .strip {
    background: var(--c-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
