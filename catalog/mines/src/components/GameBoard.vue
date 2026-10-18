<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Application,
  Assets,
  Container,
  type FederatedPointerEvent,
  Graphics,
  Sprite,
  Text,
  Texture,
  Ticker,
} from 'pixi.js'
import { playDiamond } from '../sound'
import { CELL_ALIASES, registerGameAssets } from '../preload'
import { token } from '../theme'

const props = defineProps<{
  /** Revealed diamonds, each with its cumulative multiplier. */
  cells: { index: number; multiplier: number }[]
  /** Index of the mine that was hit (FAIL), or null. */
  mineIndex: number | null
  /** Cells are clickable only while a round is active. */
  interactive: boolean
  /** Bumped by the parent to fire the cash-out accent burst. */
  celebrate: number
}>()

const emit = defineEmits<{ open: [index: number] }>()

const GRID = 5
const COUNT = GRID * GRID
const GAP = 8
const RADIUS_RATIO = 14 / 65

/** Idle cells sit back so "round not started" is visible at a glance rather
 *  than inferred from the button label. Deep enough to read as inactive, not so
 *  deep that the board stops looking like a board. */
const IDLE_ALPHA = 0.78

interface CellNode {
  container: Container
  tile: Graphics
  sprite: Sprite
  hovered: boolean
  pressed: boolean
}

const host = ref<HTMLDivElement | null>(null)
let app: Application | null = null
let resize: ResizeObserver | null = null
let tex: { gem: Texture; mine: Texture } | null = null
const cellNodes: CellNode[] = []

let viewW = 0
let viewH = 0
let cellSize = 0

let prevRevealed = new Set<number>()
let prevMine: number | null = null

// ── tiny ticker-based tween ──────────────────────────────────────────────────
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

/** Matches --e-out in tokens.css: fast on the first frames, so a tap reads as
 *  acknowledged at the instant the finger lifts. */
function easeOut(t: number): number {
  return 1 - Math.pow(1 - t, 3)
}

function backOut(t: number): number {
  const s = 1.3
  const u = t - 1
  return u * u * ((s + 1) * u + s) + 1
}

function tweenScale(c: Container, to: number, ms = 120) {
  const from = c.scale.x
  tween(ms, (t) => c.scale.set(from + (to - from) * easeOut(t)))
}

type CellLook = 'closed' | 'gem' | 'mine'

/** Paints one tile. The top inner highlight is what makes a flat rounded rect
 *  read as a physical object on a dark backdrop; a drop shadow alone does not. */
function paintTile(g: Graphics, look: CellLook, size: number, live: boolean) {
  const r = size * RADIUS_RATIO
  const fill =
    look === 'gem'
      ? token('--c-surface-2', 0x1d2836)
      : look === 'mine'
        ? 0x3a1c22
        : token('--c-surface-2', 0x1d2836)
  const edge =
    look === 'gem'
      ? token('--gem', 0x35d0e8)
      : look === 'mine'
        ? token('--c-lose', 0xff5a65)
        : live
          ? token('--c-brand', 0x2a9fe8)
          : 0xffffff

  g.clear()
    .roundRect(0, 0, size, size, r)
    .fill({ color: fill })
    .stroke({ color: edge, width: 1.5, alpha: look === 'closed' ? (live ? 0.4 : 0.14) : 0.85 })

  // Inner top-edge highlight, clipped to the upper half of the tile.
  g.roundRect(1.5, 1.5, size - 3, size * 0.5, r).stroke({
    color: 0xffffff,
    width: 1,
    alpha: 0.06,
  })
}

/** Gem: grows in with a soft overshoot. Never from scale 0 — nothing in the
 *  physical world appears out of literally nothing. */
function animateReveal(sprite: Sprite) {
  sprite.scale.set(sprite.width === 0 ? 0.6 : sprite.scale.x * 0.6)
  const target = sprite.scale.x / 0.6
  sprite.alpha = 0
  tween(
    320,
    (t) => {
      sprite.scale.set(target * (0.6 + 0.4 * backOut(t)))
      sprite.alpha = Math.min(1, t * 2.5)
    },
    () => {
      sprite.scale.set(target)
      sprite.alpha = 1
    },
  )
}

/** Mine: an impact — a damped horizontal shake plus a scale punch. */
function animateMine(cell: Container) {
  const baseX = cell.x
  tween(
    520,
    (t) => {
      const damp = 1 - t
      cell.x = baseX + Math.sin(t * Math.PI * 9) * damp * (cellSize * 0.09)
      cell.scale.set(1 + Math.sin(t * Math.PI) * 0.12)
    },
    () => {
      cell.x = baseX
      cell.scale.set(1)
    },
  )
}

/** The running multiplier pops over the tile that earned it, then floats away.
 *  This is the only place the player sees what a single safe pick was worth. */
function spawnMultiplier(cx: number, cy: number, multiplier: number) {
  if (!app) return
  const label = new Text({
    text: `×${multiplier.toFixed(2)}`,
    style: {
      fontFamily: 'Geist, sans-serif',
      fontSize: Math.max(13, cellSize * 0.38),
      fontWeight: '700',
      fill: token('--gem', 0x35d0e8),
      stroke: { color: token('--c-bg', 0x0b1017), width: Math.max(3, cellSize * 0.08) },
      align: 'center',
    },
  })
  label.anchor.set(0.5)
  label.x = cx
  label.y = cy
  app.stage.addChild(label)

  tween(
    880,
    (t) => {
      if (t < 0.24) {
        label.scale.set(backOut(t / 0.24))
        label.alpha = 1
      } else {
        const p = (t - 0.24) / 0.76
        label.y = cy - p * cellSize * 0.9
        label.alpha = 1 - p * p
      }
    },
    () => label.destroy(),
  )
}

/** Cash-out celebration: a fountain of gem-coloured sparks from the board centre. */
function spawnBurst() {
  if (!app) return
  const cx = viewW / 2
  const cy = viewH / 2
  const reach = Math.min(viewW, viewH)
  const gem = token('--gem', 0x35d0e8)
  const win = token('--c-win', 0x2ed47a)

  for (let i = 0; i < 20; i++) {
    const dot = new Graphics()
      .circle(0, 0, Math.max(3, cellSize * 0.07))
      .fill({ color: i % 3 === 0 ? win : gem })
    dot.x = cx
    dot.y = cy
    dot.blendMode = 'add'
    app.stage.addChild(dot)

    const ang = Math.random() * Math.PI * 2
    const speed = (0.45 + Math.random() * 0.55) * reach * 0.55
    const up = -reach * 0.22
    const gravity = reach * 0.7
    tween(
      720 + Math.random() * 320,
      (t) => {
        dot.x = cx + Math.cos(ang) * speed * t
        dot.y = cy + Math.sin(ang) * speed * t + up * t + gravity * t * t
        dot.alpha = 1 - t
        dot.scale.set(1 - t * 0.5)
      },
      () => dot.destroy(),
    )
  }
}

function buildCells() {
  if (!app || !tex) return
  for (let i = 0; i < COUNT; i++) {
    const container = new Container()
    const tile = new Graphics()
    const sprite = new Sprite(tex.gem)
    sprite.anchor.set(0.5)
    sprite.alpha = 0
    container.addChild(tile, sprite)

    const node: CellNode = { container, tile, sprite, hovered: false, pressed: false }

    container.on('pointerover', (e: FederatedPointerEvent) => {
      if (e.pointerType === 'touch') return
      node.hovered = true
      tweenScale(container, 1.05)
    })
    container.on('pointerout', () => {
      node.hovered = false
      if (!node.pressed) tweenScale(container, 1)
    })
    container.on('pointerdown', () => {
      node.pressed = true
      tweenScale(container, 0.93, 90)
    })
    container.on('pointerup', () => {
      node.pressed = false
      tweenScale(container, node.hovered ? 1.05 : 1)
    })
    container.on('pointerupoutside', () => {
      node.pressed = false
      tweenScale(container, 1)
    })
    container.on('pointertap', () => emit('open', i))

    app.stage.addChild(container)
    cellNodes[i] = node
  }
}

function layout() {
  if (viewW === 0 || viewH === 0 || cellNodes.length === 0) return
  const PAD = 14
  const wBased = (viewW - 2 * PAD - GAP * (GRID - 1)) / GRID
  const hBased = (viewH - 2 * PAD - GAP * (GRID - 1)) / GRID
  cellSize = Math.max(0, Math.min(wBased, hBased))
  const gridSize = cellSize * GRID + GAP * (GRID - 1)
  const originX = (viewW - gridSize) / 2
  const originY = (viewH - gridSize) / 2

  for (let i = 0; i < COUNT; i++) {
    const { container, sprite } = cellNodes[i]
    const col = i % GRID
    const row = Math.floor(i / GRID)
    container.pivot.set(cellSize / 2, cellSize / 2)
    container.x = originX + col * (cellSize + GAP) + cellSize / 2
    container.y = originY + row * (cellSize + GAP) + cellSize / 2
    // The sprite sits at the tile's centre; the tile itself is drawn from 0,0.
    sprite.position.set(cellSize / 2, cellSize / 2)
    sprite.width = cellSize * 0.62
    sprite.height = cellSize * 0.62
  }
}

function render() {
  if (!tex) return
  const multiplierByIndex = new Map(props.cells.map((c) => [c.index, c.multiplier]))
  const revealedSet = new Set(props.cells.map((c) => c.index))
  const newDiamonds = [...revealedSet].filter((i) => !prevRevealed.has(i))

  for (let i = 0; i < COUNT; i++) {
    const { container, tile, sprite } = cellNodes[i]
    const isMine = props.mineIndex === i
    const isGem = revealedSet.has(i)
    const look: CellLook = isMine ? 'mine' : isGem ? 'gem' : 'closed'
    const clickable = props.interactive && !isGem && !isMine

    paintTile(tile, look, cellSize, props.interactive)
    container.alpha = props.interactive || isGem || isMine ? 1 : IDLE_ALPHA

    if (isMine) {
      sprite.texture = tex.mine
      sprite.alpha = 1
      sprite.scale.set(1)
      sprite.width = cellSize * 0.7
      sprite.height = cellSize * 0.7
      if (prevMine !== i) animateMine(container)
    } else if (isGem) {
      sprite.texture = tex.gem
      sprite.width = cellSize * 0.62
      sprite.height = cellSize * 0.62
      if (!prevRevealed.has(i)) {
        animateReveal(sprite)
        spawnMultiplier(container.x, container.y, multiplierByIndex.get(i) ?? 1)
      } else {
        sprite.alpha = 1
      }
    } else {
      sprite.alpha = 0
      container.scale.set(1)
    }

    container.eventMode = clickable ? 'static' : 'none'
    container.cursor = clickable ? 'pointer' : 'default'
  }

  // Sound only for a live, incremental reveal — skip the bulk reveal on reconnect.
  if (newDiamonds.length === 1) playDiamond(revealedSet.size)

  prevRevealed = revealedSet
  prevMine = props.mineIndex
}

function fit() {
  if (!app || !host.value) return
  const w = host.value.clientWidth
  const h = host.value.clientHeight
  if (w === 0 || h === 0) return
  viewW = w
  viewH = h
  app.renderer.resize(w, h)
  layout()
  render()
}

onMounted(async () => {
  registerGameAssets()
  const loaded = await Assets.load([...CELL_ALIASES])
  tex = { gem: loaded['gem'], mine: loaded['mine'] }

  const pixi = new Application()
  await pixi.init({
    backgroundAlpha: 0,
    antialias: true,
    resolution: window.devicePixelRatio || 1,
    autoDensity: true,
    width: host.value?.clientWidth || 320,
    height: host.value?.clientHeight || 320,
  })
  app = pixi
  host.value?.appendChild(pixi.canvas)

  buildCells()
  fit()
  resize = new ResizeObserver(() => fit())
  if (host.value) resize.observe(host.value)
})

watch(
  () => [props.cells, props.mineIndex, props.interactive],
  () => render(),
  { deep: true },
)

watch(
  () => props.celebrate,
  (n, prev) => {
    if (n > (prev ?? 0)) spawnBurst()
  },
)

onBeforeUnmount(() => {
  resize?.disconnect()
  app?.destroy(true, { children: true })
  app = null
})
</script>

<template>
  <div ref="host" class="board" />
</template>

<style scoped>
.board {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  position: relative;
  line-height: 0;
}

.board :deep(canvas) {
  display: block;
}
</style>
