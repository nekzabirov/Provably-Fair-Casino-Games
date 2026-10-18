<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { PhSparkle, PhStar } from '@phosphor-icons/vue'
import NumberGrid from './NumberGrid.vue'
import LottoBall from './LottoBall.vue'
import { formatTon } from '../format'

// The Pencil "Bingo Card" (node HWseq), 1:1: white card, pink hairline, the
// "n of 6" header with the rainbow progress and the gold star counter, then the
// 7×7 number grid. The activity block below the grid is ours (live draw +
// result) and stays in the same card language.
const props = defineProps<{
  /** In-progress picks (pick mode) or the held ticket's numbers (locked mode). */
  selected: number[]
  winning: number[]
  mode: 'pick' | 'locked'
  finished: boolean
  /** How many numbers the draw reveals (= the "of 6" target). */
  minPicks: number
  payout?: number | null
  matched?: boolean | null
}>()

const emit = defineEmits<{ toggle: [n: number] }>()

const count = computed(() => props.selected.length)

const fillPct = computed(() => Math.min(100, (count.value / props.minPicks) * 100))

const ready = computed(() => count.value >= props.minPicks)

// Every added number thumps the whole progress row — a big, physical pulse
// (state feedback: the ticket just grew), retriggered per pick via rAF.
const bump = ref(false)
let bumpTimer: ReturnType<typeof setTimeout> | undefined
watch(count, (c, old) => {
  if (c <= (old ?? 0)) return
  bump.value = false
  requestAnimationFrame(() => (bump.value = true))
  if (bumpTimer) clearTimeout(bumpTimer)
  bumpTimer = setTimeout(() => (bump.value = false), 360)
})

// Buying locks the card: one gold flash + settle marks the moment the ticket
// stops being a draft and becomes property.
const lockin = ref(false)
watch(
  () => props.mode,
  (m, old) => {
    if (m === 'locked' && old === 'pick') {
      lockin.value = true
      setTimeout(() => (lockin.value = false), 800)
    }
  },
)

const matchCount = computed(() => {
  const win = new Set(props.winning)
  return props.selected.filter((n) => win.has(n)).length
})

/** Drawn balls in the order the engine revealed them, for the activity strip. */
const drawnBalls = computed(() => {
  const mine = new Set(props.selected)
  return props.winning.map((n) => ({ n, mine: mine.has(n) }))
})

const won = computed(() => props.finished && (props.matched ?? matchCount.value >= props.minPicks))
</script>

<template>
  <div class="card" :class="{ 'card--lockin': lockin }">
    <div class="head">
      <span :key="count" class="head__count head__count--pop tnum">{{ count }} of {{ minPicks }}</span>

      <div class="progress" :class="{ 'progress--bump': bump, 'progress--ready': ready }">
        <span class="progress__fill" :style="{ width: `${fillPct}%` }" />
        <PhSparkle
          v-if="count > 0"
          class="progress__spark"
          :style="{ left: `calc(${fillPct}% - 9px)` }"
          :size="18"
          weight="fill"
          aria-hidden="true"
        />
      </div>

      <span class="star" :class="{ 'star--full': count >= minPicks }">
        <PhStar :size="34" weight="fill" />
        <span class="star__n tnum">{{ count }}</span>
      </span>
    </div>

    <NumberGrid :selected="selected" :winning="winning" :mode="mode" @toggle="emit('toggle', $event)" />

    <!-- Live-draw activity: what the draw has done with THIS ticket. -->
    <div v-if="mode === 'locked'" class="activity">
      <div v-if="finished" class="result">
        <span class="result__label">{{ won ? 'You won' : 'No win' }}</span>
        <span v-if="won" class="result__pay tnum">+{{ formatTon(payout ?? 0) }} <i class="result__cur">Gram</i></span>
        <span v-else class="result__sub tnum">Matched {{ matchCount }} of {{ minPicks }}</span>
      </div>

      <template v-else>
        <div class="strip-head">
          <span class="strip-head__l">{{ winning.length ? 'Drawn' : 'Ticket in the draw' }}</span>
          <span class="strip-head__r tnum">
            <template v-if="winning.length">{{ matchCount }} matched</template>
          </span>
        </div>
        <div v-if="drawnBalls.length" class="strip">
          <LottoBall
            v-for="b in drawnBalls"
            :key="b.n"
            :value="b.n"
            :variant="b.mine ? 'gold' : 'drawn'"
            :size="34"
            pad
          />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
/* Node HWseq: white, r14, #E8A0A0 hairline, padding 10, gap 7. */
.card {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 10px;
  border-radius: 14px;
  border: 1px solid #e8a0a0;
  background: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
}

/* Purchase: one gold flash + settle — the ticket is now the player's. */
.card--lockin {
  animation: lockin 700ms cubic-bezier(0.23, 1, 0.32, 1);
}

@keyframes lockin {
  0% {
    transform: scale(0.985);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
  }
  35% {
    transform: scale(1.012);
    border-color: #f4c842;
    box-shadow: 0 0 34px rgba(244, 200, 66, 0.75);
  }
  100% {
    transform: scale(1);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
  }
}

/* ── header (node d1qvP8) ─────────────────────────────────────────────────── */
.head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.head__count {
  flex: none;
  color: #d51616;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

/* Each pick re-mounts the counter (:key): the new figure lands with a pop. */
.head__count--pop {
  animation: count-pop 220ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes count-pop {
  from {
    transform: scale(0.7);
  }
}

/* Node a8PXL0: the h13 r7 grey track. */
.progress {
  position: relative;
  flex: 1 1 auto;
  height: 13px;
  border-radius: 7px;
  background: #dddddd;
}

/* One big physical thump per added number: the whole bar swells and lands. */
.progress--bump {
  animation: bar-bump 340ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes bar-bump {
  0% {
    transform: scale(1, 1);
  }
  38% {
    transform: scale(1.015, 1.55);
  }
  100% {
    transform: scale(1, 1);
  }
}

/* Node O0s5y: red→orange→yellow→green fill. Width is the pick progress; the
   320ms strong ease-out makes each pick land, without dragging. */
.progress__fill {
  position: relative;
  display: block;
  height: 100%;
  border-radius: 7px;
  overflow: hidden;
  background: linear-gradient(90deg, #e01e1e 0%, #f5851f 40%, #f5d31f 70%, #3fbf3f 100%);
  transition: width 320ms cubic-bezier(0.23, 1, 0.32, 1);
}

/* Ticket complete: the full bar celebrates — a light sheen sweeps it end to
   end, continuously, until the player buys or clears. */
.progress--ready .progress__fill::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 34%;
  background: linear-gradient(105deg, transparent 0%, rgba(255, 255, 255, 0.75) 50%, transparent 100%);
  transform: translateX(-120%);
  animation: bar-sheen 1.8s ease-in-out infinite;
}

@keyframes bar-sheen {
  to {
    transform: translateX(420%);
  }
}

/* Node ugr9f: the sparkle riding the fill front. */
.progress__spark {
  position: absolute;
  top: -3px;
  color: #ffe680;
  filter: drop-shadow(0 0 4px rgba(255, 230, 128, 0.9));
  transition: left 320ms cubic-bezier(0.23, 1, 0.32, 1);
  pointer-events: none;
}

/* Node e2hccL: the gold star with the pick count inside. */
.star {
  position: relative;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  color: #f4c842;
}

.star__n {
  position: absolute;
  color: #5a3a00;
  font-size: 13px;
  font-weight: 800;
}

/* Reaching six is a state change worth a beat: one springy pop, then still. */
.star--full {
  filter: drop-shadow(0 0 6px rgba(244, 200, 66, 0.8));
  animation: star-pop 320ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes star-pop {
  0% {
    transform: scale(0.7) rotate(-12deg);
  }
  60% {
    transform: scale(1.2) rotate(6deg);
  }
  100% {
    transform: scale(1) rotate(0);
  }
}

/* ── locked-mode activity ─────────────────────────────────────────────────── */
.activity {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e8d7d7;
}

.strip-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.strip-head__l {
  color: #8a8a8a;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.strip-head__r {
  color: #333;
  font-size: 12px;
  font-weight: 700;
}

.strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.result {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.result__label {
  color: #8a8a8a;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.result__pay {
  color: #d51616;
  font-size: 22px;
  font-weight: 800;
}

.result__cur {
  font-style: normal;
  font-size: 12px;
  font-weight: 800;
  color: #c9962a;
}

.result__sub {
  color: #333;
  font-size: 14px;
  font-weight: 700;
}

@media (prefers-reduced-motion: reduce) {
  .progress__fill,
  .progress__spark {
    transition: none;
  }

  .star--full,
  .head__count--pop,
  .progress--bump,
  .progress--ready .progress__fill::after,
  .card--lockin {
    animation: none;
  }
}
</style>
