<script setup lang="ts">
import { computed } from 'vue'
import { PhCrown, PhSword } from '@phosphor-icons/vue'
import type { Seat } from '../net/protocol'
import { formatAmount, fromMinor, TON } from '../currency'

const props = defineProps<{
  roster: Seat[]
  pot: number
  /** Your seat order, to highlight your chip. */
  youOrder: number | null
}>()

// The seat ramp lives in style.css; the arena reads the same names through
// theme.ts, so a fighter's chip and their territory are always the same hue.
const PALETTE = [
  'var(--seat-1)', 'var(--seat-2)', 'var(--seat-3)', 'var(--seat-4)',
  'var(--seat-5)', 'var(--seat-6)', 'var(--seat-7)', 'var(--seat-8)',
]

// The rail shows the strongest fighters; thousands can't all be DOM chips.
const MAX_CHIPS = 24

/** The engine needs two fighters before a round can be counted down. */
const NEEDED = 2

interface Chip {
  order: number
  initial: string
  color: string
  pic?: string | null
  amount: string
  pct: string
  you: boolean
  leader: boolean
}

const sorted = computed(() => [...props.roster].sort((a, b) => b.stake - a.stake))

function pctLabel(stake: number): string {
  if (props.pot <= 0) return '0%'
  const p = (stake / props.pot) * 100
  return p < 1 ? '<1%' : `${Math.round(p)}%`
}

const chips = computed<Chip[]>(() =>
  sorted.value.slice(0, MAX_CHIPS).map((s, i) => ({
    order: s.order,
    initial: (s.username[0] ?? '?').toUpperCase(),
    color: PALETTE[s.order % PALETTE.length],
    pic: s.profilePic,
    amount: formatAmount(fromMinor(s.stake, TON), TON),
    pct: pctLabel(s.stake),
    you: s.order === props.youOrder,
    leader: i === 0,
  })),
)

const overflow = computed(() => Math.max(0, props.roster.length - MAX_CHIPS))
const fighters = computed(() => props.roster.length)
const missing = computed(() => Math.max(0, NEEDED - fighters.value))
</script>

<template>
  <!-- With no one in yet, this is the screen the player looks at most, so it
       says what the arena is waiting for and what happens when it gets it. -->
  <div v-if="chips.length === 0" class="waiting">
    <span class="waiting__icon"><PhSword :size="22" weight="fill" /></span>
    <div class="waiting__copy">
      <span class="waiting__title">Waiting for challengers</span>
      <span class="waiting__body">
        The round counts down 10 seconds once {{ NEEDED }} fighters are in. Enter now to hold
        the whole wheel until someone answers.
      </span>
    </div>
    <div class="slots" :aria-label="`0 of ${NEEDED} fighters`">
      <span class="slots__n tnum">0/{{ NEEDED }}</span>
      <span class="slots__l">in</span>
    </div>
  </div>

  <div v-else class="strip">
    <span class="count">
      <span class="count__n tnum">{{ fighters }}</span>
      <small>{{ missing > 0 ? `${missing} more to start` : 'in the arena' }}</small>
    </span>
    <TransitionGroup name="fighter" tag="div" class="rail">
      <div
        v-for="c in chips"
        :key="c.order"
        class="chip"
        :class="{ you: c.you, leader: c.leader }"
      >
        <PhCrown v-if="c.leader" class="crown" :size="13" weight="fill" />
        <span class="dot" :style="{ background: c.color }">
          <img v-if="c.pic" :src="c.pic" alt="" />
          <template v-else>{{ c.initial }}</template>
        </span>
        <span class="meta">
          <span class="amt tnum">{{ c.amount }}</span>
          <span class="pct tnum">{{ c.pct }}</span>
        </span>
      </div>
      <div v-if="overflow > 0" key="overflow" class="chip more">
        <span class="more__n tnum">+{{ overflow }}</span>
        <span class="more__l">fighters</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
/* ── waiting (empty) ──────────────────────────────────────────────────────── */
.waiting {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 var(--pad-x) 4px;
  padding: 12px 14px;
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  box-shadow: var(--sh-1);
}

.waiting__icon {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--r-chip);
  background: var(--arena-gold-soft);
  color: var(--arena-gold);
}

.waiting__copy {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.waiting__title {
  font-size: 14px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1.2;
}

.waiting__body {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--c-text-2);
  line-height: 1.35;
}

/* The gate: how many of the required fighters are in. */
.slots {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 7px 9px;
  border-radius: var(--r-ctl);
  background: var(--c-bg);
  border: 1px solid var(--c-line);
}

.slots__n {
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text-2);
  line-height: 1;
}

.slots__l {
  font-size: 8.5px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--c-text-3);
  line-height: 1;
}

/* ── rail ─────────────────────────────────────────────────────────────────── */
.strip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px var(--pad-x);
  min-height: 56px;
  /* NOT hidden: the leader crown pokes above each chip and the "you" chip has an
     outer ring - clipping them here cropped the card at the top. The rail owns
     horizontal scrolling; nothing spills. */
  overflow: visible;
}

.count {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  white-space: nowrap;
}

.count__n {
  font-size: 15px;
  font-weight: 700;
  color: var(--arena-gold);
  line-height: 1;
}

.count small {
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--c-text-3);
  line-height: 1;
}

.rail {
  display: flex;
  gap: 8px;
  align-items: center;
  overflow-x: auto;
  scrollbar-width: none;
  /* Breathing room inside the scrollport so the leader crown (pokes ~8px above a
     chip) and the "you" ring fit without being clipped into a hard square. */
  padding: 11px 8px;
}

.rail::-webkit-scrollbar {
  display: none;
}

.chip {
  position: relative;
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 0 0 auto;
  padding: 4px 11px 4px 4px;
  border-radius: var(--r-chip);
  background: var(--c-surface);
  border: 1px solid var(--c-line);
}

.chip.you {
  border-color: var(--arena-gold);
  box-shadow: 0 0 0 1px var(--arena-gold), 0 0 10px var(--arena-glow);
}

.chip.leader {
  background: var(--c-surface-2);
}

.crown {
  position: absolute;
  top: -8px;
  left: 5px;
  color: var(--arena-gold);
  filter: drop-shadow(0 1px 2px rgba(3, 7, 12, 0.7));
  transform: rotate(-16deg);
}

.dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  overflow: hidden;
  color: var(--c-bg);
  font-size: 13px;
  font-weight: 700;
}

.dot img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1;
}

.amt {
  font-size: 13px;
  font-weight: 700;
  color: var(--c-text);
}

.pct {
  font-size: 10px;
  font-weight: 600;
  color: var(--c-text-3);
}

.more {
  padding: 6px 12px;
  flex-direction: column;
  gap: 2px;
  background: var(--c-surface-2);
}

.more__n {
  font-size: 13px;
  font-weight: 700;
  color: var(--c-text-2);
  line-height: 1;
}

.more__l {
  font-size: 8px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--c-text-3);
}

/* A new fighter arriving is information: it changes everyone's odds. */
.fighter-enter-active {
  transition: transform 0.32s cubic-bezier(0.2, 1.5, 0.4, 1), opacity 0.32s var(--e-out);
}
.fighter-leave-active {
  transition: transform 0.2s var(--e-out), opacity 0.2s var(--e-out);
  position: absolute;
}
.fighter-enter-from {
  transform: translateY(-14px) scale(0.6);
  opacity: 0;
}
.fighter-leave-to {
  transform: scale(0.6);
  opacity: 0;
}
.fighter-move {
  transition: transform 0.3s var(--e-out);
}

@media (prefers-reduced-motion: reduce) {
  .fighter-enter-active,
  .fighter-leave-active,
  .fighter-move {
    transition: none;
  }
}
</style>
