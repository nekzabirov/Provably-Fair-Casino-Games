<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PvpScene } from '../game/PvpScene'
import type { Phase, Seat } from '../net/protocol'
import { playClack } from '../sound'

const props = defineProps<{
  roster: Seat[]
  phase: Phase
  /** Epoch millis the countdown ends - drives the center seconds + charge ring. */
  phaseEndsAt: number
  /** Countdown length (ms), so the charge ring knows how full it should be. */
  countdownMs: number
  /** Local player's seat order, to glow their territory. */
  youOrder: number | null
}>()

const emit = defineEmits<{ landed: [] }>()

const SPIN_MS = 5400

const host = ref<HTMLDivElement | null>(null)
const centerText = ref('OPEN')
const centerMode = ref<'idle' | 'count' | 'number'>('idle')
/** Last three seconds: the only moment the readout is allowed to shout. */
const urgent = ref(false)
let scene: PvpScene | null = null
let resize: ResizeObserver | null = null
let countdownTimer = 0
let spinningNumber = false

/** Strike the pointer onto the winning seat; resolves visually via 'landed'. */
function spinTo(winnerOrder: number, winningNumber: number): void {
  if (!scene) return
  spinningNumber = true
  centerMode.value = 'number'
  scene.spin(winnerOrder, winningNumber, SPIN_MS, {
    onNumber: (n) => (centerText.value = String(Math.round(n))),
    onClack: () => playClack(),
    onLanded: () => {
      spinningNumber = false
      centerText.value = String(Math.round(winningNumber))
      emit('landed')
    },
  })
}

defineExpose({ spinTo })

const caption = computed(() => {
  if (centerMode.value === 'number') return 'RESULT'
  if (centerMode.value === 'count') return 'BETS CLOSE IN'
  return 'NEXT ROUND'
})

function refreshCountdown(): void {
  if (props.phase !== 'COUNTDOWN') return
  const remaining = props.phaseEndsAt - Date.now()
  const secs = Math.max(0, Math.ceil(remaining / 1000))
  centerText.value = String(secs)
  urgent.value = secs <= 3
  const span = props.countdownMs > 0 ? props.countdownMs : 10_000
  scene?.setCharge(Math.min(1, Math.max(0, 1 - remaining / span)))
}

watch(
  () => props.roster,
  (r) => scene?.setRoster(r),
  { deep: true },
)

watch(
  () => props.youOrder,
  (o) => scene?.setYou(o),
)

watch(
  () => props.phase,
  (p) => {
    scene?.setIdle(p === 'WAITING' || p === 'COUNTDOWN')
    if (p !== 'COUNTDOWN') scene?.setCharge(null)
    if (p === 'WAITING') {
      if (!spinningNumber) {
        centerMode.value = 'idle'
        centerText.value = 'OPEN'
        urgent.value = false
      }
    } else if (p === 'COUNTDOWN') {
      centerMode.value = 'count'
      refreshCountdown()
    }
  },
)

onMounted(async () => {
  const s = new PvpScene()
  await s.init(host.value!)
  scene = s
  scene.setRoster(props.roster)
  scene.setYou(props.youOrder)
  scene.setIdle(props.phase === 'WAITING' || props.phase === 'COUNTDOWN')
  const fit = () => {
    if (host.value && scene) scene.resize(host.value.clientWidth, host.value.clientHeight)
  }
  fit()
  resize = new ResizeObserver(fit)
  if (host.value) resize.observe(host.value)
  countdownTimer = window.setInterval(refreshCountdown, 200)
})

onBeforeUnmount(() => {
  window.clearInterval(countdownTimer)
  resize?.disconnect()
  scene?.destroy()
  scene = null
})
</script>

<template>
  <div class="wheel">
    <div ref="host" class="canvas" />
    <!-- The hub readout: one line of state, one big value. It never shows two
         things at once, so a glance is always enough. -->
    <div class="center" :class="[centerMode, { urgent }]">
      <span class="center__cap">{{ caption }}</span>
      <span class="center__value tnum">{{ centerText }}</span>
      <span v-if="centerMode === 'count'" class="center__unit">seconds</span>
      <span v-else-if="centerMode === 'idle'" class="center__unit">join anytime</span>
    </div>
  </div>
</template>

<style scoped>
.wheel {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  display: flex;
}

.canvas {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  line-height: 0;
}

.canvas :deep(canvas) {
  display: block;
}

/* The core readout over the Pixi hub. */
.center {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  pointer-events: none;
  text-align: center;
}

.center__cap {
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--c-text-3);
  line-height: 1;
}

.center__value {
  font-size: 46px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--c-text);
  line-height: 1;
}

.center__unit {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
  line-height: 1;
}

/* Idle: the hub states that the arena is open, quietly. */
.center.idle .center__value {
  font-size: 26px;
  letter-spacing: 0.1em;
  color: var(--arena-gold);
}

.center.number .center__cap {
  color: var(--arena-gold);
}

.center.number .center__value {
  color: var(--arena-gold);
  text-shadow: 0 0 22px var(--arena-glow);
}

/* Countdown: the number is already the loudest thing in the hub, so urgency is
   carried by colour and a single beat per second - not a continuous pulse. */
.center.count .center__cap {
  color: var(--arena-gold);
}

.center.count .center__value {
  color: var(--arena-gold);
  text-shadow: 0 0 20px var(--arena-glow);
}

.center.count.urgent .center__cap,
.center.count.urgent .center__unit {
  color: var(--c-lose);
}

.center.count.urgent .center__value {
  color: var(--c-lose);
  text-shadow: 0 0 24px var(--c-lose-soft);
  animation: tick 1s var(--e-out) infinite;
}

@keyframes tick {
  0% { transform: scale(1.16); }
  22%, 100% { transform: scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .center.count.urgent .center__value {
    animation: none;
  }
}
</style>
