<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Application, Assets, Container, Graphics, Sprite, Text, Texture, type Ticker } from 'pixi.js'
import type { LiveState } from '../composables/useTeleFly'
import { token, tokenRgb } from '../theme'

const props = defineProps<{ live: LiveState }>()

// Canvas colours are read from the CSS tokens, so the scene can never drift out
// of sync with the chrome around it. Fallbacks match tokens.css.
const WHITE = 0xffffff
const TRAIL = token('--trail', 0x2a9fe8)
const TRAIL_EDGE = token('--trail-edge', 0x7fd8ff)
const TRAIL_RGB = tokenRgb('--trail', 0x2a9fe8)
const LOSE = token('--c-lose', 0xff5a65)
const WIN = token('--c-win', 0x2ed47a)
const GRID = token('--c-text-3', 0x6b7c91)

const X_WINDOW_MS = 5200
const TAU = 80
const SAMPLE_MS = 25
const PUFF_MS = 90

/** Altitude rungs. The plot is logarithmic, so without them the player cannot
 *  tell how far the next payout tier is. This is what fills the mid-screen. */
const RUNGS = [1.5, 2, 3, 5, 10, 25, 100]

const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

const host = ref<HTMLDivElement | null>(null)
let app: Application | null = null
let resize: ResizeObserver | null = null
let planeTex: Texture | null = null
let gradientTex: Texture | null = null
let disposed = false

// scene nodes
let grid: Graphics | null = null
let rungLabels: Container | null = null
let trailFill: Sprite | null = null
let trailMask: Graphics | null = null
let trailLine: Graphics | null = null
let puffLayer: Container | null = null
let plane: Container | null = null
let planeSprite: Sprite | null = null
let fx: Container | null = null
let flybyLayer: Container | null = null

// layout
let viewW = 0
let viewH = 0
let baseY = 0
let topY = 0
let plotH = 0
let anchorX = 0
let speed = 0
let planeScale = 1
let launchX = 0

// flight state
let localPhase: LiveState['phase'] = 'CRASHED'
let elapsedMs = 0
let shown = 1
let prevShown = 1
const points: { wx: number; mult: number }[] = []
let sampleAccum = 0
let puffAccum = 0
let idlePhase = 0
let planePrevX = 0
let planePrevY = 0

// crash state
let crashing = false
let crashT = 0
let crashFromX = 0
let crashFromY = 0
let crashRot = 0

function tween(durationMs: number, onUpdate: (t: number) => void, onDone?: () => void) {
  if (!app) return
  let elapsed = 0
  const step = (ticker: Ticker) => {
    elapsed += ticker.deltaMS
    const t = Math.min(1, elapsed / durationMs)
    onUpdate(t)
    if (t >= 1) {
      app?.ticker.remove(step)
      onDone?.()
    }
  }
  app.ticker.add(step)
}

/** Vertical falloff painted under the ascent curve. A flat fill reads as an
 *  unfinished block; a gradient reads as altitude haze the plane climbs out of. */
function buildGradientTexture(): Texture {
  const c = document.createElement('canvas')
  c.width = 4
  c.height = 256
  const g = c.getContext('2d')!
  const grad = g.createLinearGradient(0, 0, 0, 256)
  grad.addColorStop(0, `rgba(${TRAIL_RGB}, 0.5)`)
  grad.addColorStop(0.3, `rgba(${TRAIL_RGB}, 0.24)`)
  grad.addColorStop(0.68, `rgba(${TRAIL_RGB}, 0.08)`)
  grad.addColorStop(1, `rgba(${TRAIL_RGB}, 0.01)`)
  g.fillStyle = grad
  g.fillRect(0, 0, 4, 256)
  return Texture.from(c)
}

function buildPlaneFallback(): Container {
  const c = new Container()
  c.addChild(new Graphics().poly([22, 0, -18, -13, -2, 0, -18, 13]).fill({ color: TRAIL_EDGE }))
  return c
}

function y(mult: number): number {
  // The engine grows the multiplier exponentially with time, so plotting log2 of
  // it against elapsed time gives a straight climb. tanh is near-linear over the
  // common 1x-5x range and only compresses past that, which keeps the ascent an
  // honest diagonal instead of the near-vertical hook a 1-1/(1+kL) curve draws.
  // It also saturates, so the plot never has to rescale mid-flight.
  const yn = Math.tanh(Math.log2(Math.max(1, mult)) / 3.2)
  return baseY - yn * plotH
}

function drawGrid(): void {
  if (!grid || !rungLabels) return
  grid.clear()
  rungLabels.removeChildren()

  // Horizon: the line the plane launches from.
  grid.moveTo(0, baseY).lineTo(viewW, baseY).stroke({ color: GRID, width: 1, alpha: 0.5 })

  for (const m of RUNGS) {
    const ry = y(m)
    if (ry < topY + 6 || ry > baseY - 14) continue
    // Dashed, so a rung never competes with the solid ascent line.
    for (let x = 8; x < viewW - 44; x += 14) {
      grid.moveTo(x, ry).lineTo(x + 7, ry)
    }
    grid.stroke({ color: GRID, width: 1, alpha: 0.28 })

    const label = new Text({
      text: `×${m}`,
      style: {
        fontFamily: 'Geist, system-ui, sans-serif',
        fontSize: 11,
        fontWeight: '700',
        fill: GRID,
        letterSpacing: 0.4,
      },
    })
    label.anchor.set(1, 0.5)
    label.x = viewW - 10
    label.y = ry
    label.alpha = 0.9
    rungLabels.addChild(label)
  }
}

function resetFlight(): void {
  elapsedMs = 0
  shown = 1
  prevShown = 1
  planePrevX = launchX
  planePrevY = baseY
  points.length = 0
  crashing = false
  crashT = 0
  sampleAccum = 0
  puffAccum = 0
  if (plane) {
    plane.alpha = 1
    plane.visible = true
    plane.rotation = 0
  }
  fx?.removeChildren()
  puffLayer?.removeChildren()
  trailMask?.clear()
  trailLine?.clear()
  if (trailFill) trailFill.visible = false
}

/** Reconnecting into a round already in the air. Without a past to draw, the
 *  first frame would put a vertical wall from the launch line straight up to the
 *  live multiplier, so the ascent that already happened is reconstructed from the
 *  same time/altitude relation the live curve follows. */
function catchUp(target: number): void {
  const CATCH_SAMPLES = 40
  const totalMs = Math.tanh(Math.log2(target) / 3.2) * X_WINDOW_MS
  for (let i = 0; i <= CATCH_SAMPLES; i++) {
    const ms = (totalMs * i) / CATCH_SAMPLES
    const yn = Math.min(0.999, ms / X_WINDOW_MS)
    points.push({ wx: ms * speed, mult: Math.max(1, 2 ** (Math.atanh(yn) * 3.2)) })
  }
  shown = prevShown = target
  elapsedMs = totalMs
}

function drawTrail(cameraX: number, headX: number, headY: number): void {
  if (!trailMask || !trailLine || !trailFill || points.length < 1) return
  const px = (p: { wx: number }) => launchX + p.wx - cameraX

  // Mask: the closed area between the curve and the horizon. The gradient sprite
  // behind it supplies the falloff, so there is no hard-edged block anywhere.
  trailMask.clear()
  trailMask.moveTo(px(points[0]), baseY)
  for (const p of points) trailMask.lineTo(px(p), y(p.mult))
  trailMask.lineTo(headX, headY)
  // Slanted trailing edge: closing straight down to the horizon would put a hard
  // vertical wall right under the plane, which is what made the old fill look
  // like an unfinished block.
  trailMask.lineTo(Math.max(px(points[0]), headX - 26), baseY)
  trailMask.fill({ color: WHITE })
  // The falloff is anchored to the head of the curve, not to the viewport, so the
  // haze always starts dense right under the trail and fades toward the horizon
  // however high the plane has climbed.
  trailFill.visible = true
  trailFill.y = headY - 6
  trailFill.height = Math.max(1, baseY - headY + 6)

  // Line: a wide soft pass under a crisp one reads as a glowing vapour trail.
  trailLine.clear()
  trailLine.moveTo(px(points[0]), y(points[0].mult))
  for (const p of points) trailLine.lineTo(px(p), y(p.mult))
  trailLine.lineTo(headX, headY)
  trailLine.stroke({ color: TRAIL, width: 26, alpha: 0.09, cap: 'round', join: 'round' })

  trailLine.moveTo(px(points[0]), y(points[0].mult))
  for (const p of points) trailLine.lineTo(px(p), y(p.mult))
  trailLine.lineTo(headX, headY)
  trailLine.stroke({ color: TRAIL, width: 12, alpha: 0.26, cap: 'round', join: 'round' })

  trailLine.moveTo(px(points[0]), y(points[0].mult))
  for (const p of points) trailLine.lineTo(px(p), y(p.mult))
  trailLine.lineTo(headX, headY)
  trailLine.stroke({ color: TRAIL_EDGE, width: 3, alpha: 0.95, cap: 'round', join: 'round' })

  trailLine.circle(headX, headY, 5).fill({ color: WHITE, alpha: 0.9 })
}

function spawnPuff(px: number, py: number): void {
  if (!puffLayer || reduceMotion) return
  const g = new Graphics().circle(0, 0, 3 + Math.random() * 4).fill({ color: WHITE, alpha: 0.3 })
  g.x = px
  g.y = py
  puffLayer.addChild(g)
  const dx = -22 - Math.random() * 22
  const dy = 6 + Math.random() * 10
  tween(
    760,
    (t) => {
      g.x = px + dx * t
      g.y = py + dy * t
      g.alpha = 0.3 * (1 - t)
      g.scale.set(1 + t * 1.7)
    },
    () => g.destroy(),
  )
}

function spawnExplosion(px: number, py: number): void {
  if (!fx) return
  const reach = Math.min(viewW, viewH)
  const colors = [LOSE, TRAIL, WIN]
  const count = reduceMotion ? 0 : 24
  for (let i = 0; i < count; i++) {
    const dot = new Graphics().circle(0, 0, 3 + Math.random() * 5).fill({ color: colors[i % colors.length] })
    dot.x = px
    dot.y = py
    dot.blendMode = 'add'
    fx.addChild(dot)
    const ang = Math.random() * Math.PI * 2
    const sp = (0.4 + Math.random() * 0.6) * reach * 0.55
    const gravity = reach * 0.8
    tween(
      700 + Math.random() * 350,
      (t) => {
        dot.x = px + Math.cos(ang) * sp * t
        dot.y = py + Math.sin(ang) * sp * t + gravity * t * t * 0.5
        dot.alpha = 1 - t
        dot.scale.set(1 - t * 0.5)
      },
      () => dot.destroy(),
    )
  }
  const flash = new Graphics().rect(0, 0, viewW, viewH).fill({ color: LOSE })
  fx.addChild(flash)
  tween(
    300,
    (t) => (flash.alpha = (1 - t) * 0.34),
    () => flash.destroy(),
  )
}

function spawnFlyby(name: string, detail: string): void {
  if (!flybyLayer || flybyLayer.children.length >= 6) return
  const c = new Container()
  const mini = planeTex ? new Sprite(planeTex) : new Graphics().poly([14, 0, -10, -8, -10, 8]).fill({ color: TRAIL })
  if (mini instanceof Sprite) {
    mini.anchor.set(0.5)
    mini.scale.set((Math.min(viewW, viewH) * 0.1) / (planeTex!.width || 100))
  }
  const label = new Text({
    text: `${name}\n${detail}`,
    style: {
      fontFamily: 'Geist, system-ui, sans-serif',
      fontSize: Math.max(10, Math.min(viewW, viewH) * 0.032),
      fontWeight: '700',
      fill: WHITE,
      align: 'center',
      stroke: { color: token('--c-bg', 0x0b1017), width: 4 },
      lineHeight: Math.max(13, Math.min(viewW, viewH) * 0.04),
    },
  })
  label.anchor.set(0.5, 1)
  label.y = -(mini instanceof Sprite ? mini.height : 16) * 0.7
  c.addChild(mini, label)
  const yStart = viewH * (0.32 + Math.random() * 0.2)
  const driftY = (Math.random() - 0.5) * 50
  c.x = viewW + 140
  c.y = yStart
  flybyLayer.addChild(c)
  tween(
    4400,
    (t) => {
      c.x = viewW + 140 - (viewW + 360) * t
      c.y = yStart + Math.sin(t * Math.PI * 2) * 10 + driftY * t
      c.alpha = t < 0.1 ? t / 0.1 : t > 0.85 ? (1 - t) / 0.15 : 1
    },
    () => c.destroy(),
  )
}

function loop(ticker: Ticker): void {
  if (!app || !plane) return
  const dt = ticker.deltaMS
  const live = props.live

  // Drain cashout flybys.
  let drained = 0
  while (live.flybys.length && drained < 2) {
    const f = live.flybys.shift()!
    spawnFlyby(f.name, f.detail)
    drained++
  }

  // Phase transitions.
  if (live.phase !== localPhase) {
    if (live.phase === 'FLYING' || live.phase === 'BETTING') resetFlight()
    if (live.phase === 'CRASHED') {
      crashing = true
      crashT = 0
      crashFromX = plane.x
      crashFromY = plane.y
      crashRot = plane.rotation
      spawnExplosion(plane.x, plane.y)
    }
    localPhase = live.phase
  }

  if (live.phase === 'FLYING') {
    if (points.length === 0 && live.targetMultiplier > 1.02) catchUp(live.targetMultiplier)
    elapsedMs += dt
    const target = live.targetMultiplier
    shown += (target - shown) * (1 - Math.exp(-dt / TAU))
    shown = Math.max(shown, prevShown)
    prevShown = shown
    live.shownMultiplier = shown

    const worldX = elapsedMs * speed
    const headX = launchX + worldX
    const cameraX = Math.max(0, headX - anchorX)
    const sx = headX - cameraX // settles at anchorX once it scrolls
    const sy = y(shown)

    sampleAccum += dt
    if (sampleAccum >= SAMPLE_MS || points.length === 0) {
      sampleAccum = 0
      points.push({ wx: worldX, mult: shown })
    }
    while (points.length > 2 && launchX + points[0].wx - cameraX < -40) points.shift()
    drawTrail(cameraX, sx, sy)

    // Rotation from the plane's own per-frame velocity (continuous, so no twitch),
    // heavily eased.
    const dx = sx - planePrevX
    const dy = sy - planePrevY
    if (Math.hypot(dx, dy) > 0.4) {
      const targetRot = Math.atan2(dy, dx) * 0.5
      plane.rotation += (targetRot - plane.rotation) * 0.08
    }
    planePrevX = sx
    planePrevY = sy
    plane.x = sx
    plane.y = sy
    plane.visible = true

    puffAccum += dt
    if (puffAccum >= PUFF_MS) {
      puffAccum = 0
      spawnPuff(sx - Math.cos(plane.rotation) * planeScale * 54, sy - Math.sin(plane.rotation) * planeScale * 54)
    }
  } else if (live.phase === 'CRASHED') {
    if (crashing) {
      crashT += dt
      const t = Math.min(1, crashT / 950)
      const ease = t * t
      plane.x = crashFromX + 0.4 * viewW * ease
      plane.y = crashFromY + viewH * 0.95 * ease
      plane.rotation = crashRot + (1.5 - crashRot) * ease
      plane.alpha = Math.max(0, 1 - t * 1.2)
      if (t >= 1) crashing = false
    }
  } else {
    // BETTING: the plane holds on the horizon, nose already lifted.
    idlePhase += dt / 420
    plane.visible = true
    plane.alpha = 1
    plane.x = launchX
    plane.y = baseY - 14 + (reduceMotion ? 0 : Math.sin(idlePhase) * 4)
    plane.rotation = -0.12
    planePrevX = plane.x
    planePrevY = plane.y
  }
}

function fit(): void {
  if (!app || !host.value) return
  const w = host.value.clientWidth
  const h = host.value.clientHeight
  if (w === 0 || h === 0) return
  viewW = w
  viewH = h
  app.renderer.resize(w, h)

  // Reserve the bottom band for the position bar + control panel, and the top
  // band for the HUD, history and the multiplier readout. The plot fills the rest
  // exactly, so there is no empty middle.
  const PANEL_RESERVE = Math.min(290, Math.max(206, viewH * 0.315))
  baseY = viewH - PANEL_RESERVE
  topY = viewH * 0.24
  plotH = baseY - topY
  anchorX = viewW * 0.68
  launchX = viewW * 0.16
  speed = anchorX / X_WINDOW_MS
  planeScale = Math.max(1, Math.min(viewW, viewH) / 360)

  if (planeSprite) {
    planeSprite.scale.set((Math.min(viewW, viewH) * 0.3) / (planeTex?.width || 240))
  } else if (plane) {
    plane.scale.set(planeScale)
  }
  if (trailFill) {
    trailFill.x = 0
    trailFill.width = viewW
    trailFill.y = topY
    trailFill.height = Math.max(1, baseY - topY)
  }
  drawGrid()
}

onMounted(async () => {
  const pixi = new Application()
  await pixi.init({
    backgroundAlpha: 0,
    antialias: true,
    resolution: window.devicePixelRatio || 1,
    autoDensity: true,
    width: host.value?.clientWidth || 320,
    height: host.value?.clientHeight || 480,
  })
  // The component can unmount while we await (e.g. a session error swaps in the
  // error screen) - bail instead of building on a torn-down app.
  if (disposed) {
    pixi.destroy(true, { children: true })
    return
  }
  app = pixi
  host.value?.appendChild(pixi.canvas)
  if (import.meta.env.DEV) (window as unknown as { __teleflyApp: Application }).__teleflyApp = pixi

  // Rung labels must not paint in a fallback face and then reflow to Geist.
  await document.fonts.ready.catch(() => undefined)
  planeTex = await Assets.load('/plane.svg').catch(() => null)
  if (disposed) {
    pixi.destroy(true, { children: true })
    app = null
    return
  }

  grid = new Graphics()
  rungLabels = new Container()
  gradientTex = buildGradientTexture()
  trailFill = new Sprite(gradientTex)
  trailFill.visible = false
  trailMask = new Graphics()
  trailFill.mask = trailMask
  trailLine = new Graphics()
  puffLayer = new Container()
  fx = new Container()
  flybyLayer = new Container()

  plane = new Container()
  if (planeTex) {
    planeSprite = new Sprite(planeTex)
    planeSprite.anchor.set(0.5)
    plane.addChild(planeSprite)
  } else {
    plane.addChild(buildPlaneFallback())
  }

  pixi.stage.addChild(grid, rungLabels, trailFill, trailMask, trailLine, puffLayer, plane, fx, flybyLayer)

  fit()
  pixi.ticker.add(loop)
  resize = new ResizeObserver(() => fit())
  if (host.value) resize.observe(host.value)
})

onBeforeUnmount(() => {
  disposed = true
  resize?.disconnect()
  if (app) app.ticker.remove(loop)
  app?.destroy(true, { children: true })
  app = null
  gradientTex?.destroy(true)
  gradientTex = null
})
</script>

<template>
  <div ref="host" class="canvas" />
</template>

<style scoped>
.canvas {
  width: 100%;
  height: 100%;
  position: relative;
  line-height: 0;
}

.canvas :deep(canvas) {
  display: block;
}
</style>
