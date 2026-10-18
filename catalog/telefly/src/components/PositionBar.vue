<script lang="ts">
/** What the player has riding on the round right now. */
export type Position = 'idle' | 'open' | 'placed' | 'live' | 'cashed' | 'lost'
</script>

<script setup lang="ts">
import { computed } from 'vue'
import { PhAirplaneTakeoff, PhCheckCircle, PhEye, PhTarget, PhXCircle } from '@phosphor-icons/vue'
import TonIcon from './TonIcon.vue'

const props = defineProps<{
  status: Position
  /** Pre-formatted stake, empty when there is no bet. */
  stake: string
  /** Pre-formatted payout: live cash-out value, or the settled amount. */
  value: string
  /** Multiplier the player cashed out at, for the settled line. */
  multiplier: string
}>()

const icon = computed(() => {
  switch (props.status) {
    case 'placed':
    case 'live':
      return PhAirplaneTakeoff
    case 'open':
      return PhTarget
    case 'cashed':
      return PhCheckCircle
    case 'lost':
      return PhXCircle
    default:
      return PhEye
  }
})

const caption = computed(() => {
  switch (props.status) {
    case 'open':
      return 'Place a bet before takeoff'
    case 'placed':
      return 'Bet in, takes off next'
    case 'live':
      return 'Your bet is flying'
    case 'cashed':
      return `Cashed out ×${props.multiplier}`
    case 'lost':
      return 'Lost this round'
    default:
      return 'No bet this round'
  }
})

const valueLabel = computed(() => {
  switch (props.status) {
    case 'live':
      return 'Cash out now'
    case 'cashed':
      return 'Paid'
    default:
      return 'Lost'
  }
})

// Before takeoff the stake sub-line already says what is at risk; repeating it in
// the value column would be two numbers saying the same thing.
const showValue = computed(() => props.status === 'live' || props.status === 'cashed' || props.status === 'lost')
</script>

<template>
  <div class="pos" :class="status">
    <component :is="icon" class="pos__icon" :size="18" weight="fill" />

    <div class="pos__text">
      <span class="pos__caption">{{ caption }}</span>
      <span v-if="stake" class="pos__stake tnum">Stake {{ stake }}</span>
    </div>

    <div v-if="showValue" class="pos__value">
      <span class="pos__value-l">{{ valueLabel }}</span>
      <span class="pos__value-v">
        <span class="tnum">{{ status === 'lost' ? stake : value }}</span>
        <TonIcon :size="13" />
      </span>
    </div>
  </div>
</template>

<style scoped>
/* Sits directly above the action button so the eye lands on it on the way down
   from the multiplier: what am I in for, what does tapping pay. */
.pos {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 52px;
  margin: 0 var(--pad-x) 8px;
  padding: 8px 13px;
  border-radius: var(--r-ctl);
  background: rgba(21, 30, 42, 0.78);
  border: 1px solid var(--c-line);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.pos__icon {
  flex: none;
  color: var(--c-text-3);
}

.pos.placed .pos__icon {
  color: var(--c-brand);
}

.pos.live .pos__icon,
.pos.cashed .pos__icon {
  color: var(--c-win);
}

.pos.lost .pos__icon {
  color: var(--c-lose);
}

.pos.placed {
  border-color: rgba(42, 159, 232, 0.4);
}

.pos.live,
.pos.cashed {
  border-color: rgba(46, 212, 122, 0.4);
}

.pos.lost {
  border-color: rgba(255, 90, 101, 0.34);
}

.pos__text {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.pos__caption {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--c-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pos.idle .pos__caption,
.pos.open .pos__caption {
  color: var(--c-text-2);
}

.pos.open .pos__icon {
  color: var(--c-brand);
}

.pos__stake {
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  color: var(--c-text-3);
}

.pos__value {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
}

.pos__value-l {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  line-height: 1;
  color: var(--c-text-3);
}

.pos__value-v {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 17px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.01em;
  color: var(--c-text);
}

.pos.live .pos__value-v,
.pos.cashed .pos__value-v {
  color: var(--c-win);
}

.pos.lost .pos__value-v {
  color: var(--c-lose);
}

@media (prefers-reduced-transparency: reduce) {
  .pos {
    background: var(--c-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
