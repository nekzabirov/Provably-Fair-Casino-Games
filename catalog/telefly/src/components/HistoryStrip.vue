<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  /** Recent crash multipliers, oldest first. */
  history: number[]
}>()

// Newest on the right. Five is what fits at a size worth reading on a 390px
// phone; more would clip off the left edge and read as broken.
const pills = computed(() => props.history.slice(-5))

// Same four tiers the in-flight readout uses, so a x5 here and a x5 in the air
// are the same colour.
function tone(m: number): string {
  if (m < 1.5) return 'low'
  if (m < 2) return 'mid'
  if (m < 10) return 'high'
  return 'huge'
}
</script>

<template>
  <div class="strip" aria-label="Recent crash multipliers">
    <span v-for="(m, i) in pills" :key="i" class="pill tnum" :class="tone(m)">×{{ m.toFixed(2) }}</span>
  </div>
</template>

<style scoped>
.strip {
  display: flex;
  gap: 6px;
  padding: 4px var(--pad-x) 2px;
  overflow-x: auto;
  scrollbar-width: none;
  justify-content: flex-end;
  /* Purely a readout; it must never eat a tap meant for the flight area. */
  pointer-events: none;
}

.strip::-webkit-scrollbar {
  display: none;
}

/* Colour is the whole point of the strip - a player scans it for how often the
   round busts early. Filled tint plus a matching border makes the tier readable
   at a glance instead of a grey row of near-identical chips. */
.pill {
  flex: none;
  padding: 7px 12px;
  border-radius: var(--r-chip);
  border: 1px solid transparent;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.01em;
}

.pill.low {
  color: var(--c-lose);
  background: var(--c-lose-soft);
  border-color: rgba(255, 90, 101, 0.28);
}

.pill.mid {
  color: var(--c-text-2);
  background: var(--c-surface);
  border-color: var(--c-line);
}

.pill.high {
  color: var(--c-brand);
  background: var(--c-brand-soft);
  border-color: rgba(42, 159, 232, 0.28);
}

.pill.huge {
  color: var(--c-win);
  background: var(--c-win-soft);
  border-color: rgba(46, 212, 122, 0.3);
}
</style>
