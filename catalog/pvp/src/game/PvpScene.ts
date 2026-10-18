import { Application, Container, Graphics, Sprite, Text, Texture } from 'pixi.js'
import type { Seat } from '../net/protocol'
import { token } from '../theme'

/**
 * The PvP battle arena - a colosseum, not a roulette table.
 *
 * The arena is a ring, not a pie: a heavy machined bezel, a territory track,
 * and a solid hub that carries the readout. Every combatant owns a proportional
 * band of the track (their "territory") and stands on it as a real avatar.
 * A gold striker sweeps the rim; as it passes a territory it strikes that
 * fighter (flash + recoil), and on lock it slams to a decisive stop and the
 * winning territory lights up.
 *
 * ── Colour is data ───────────────────────────────────────────────────────────
 * Every colour is read from the CSS tokens through `theme.ts`, so the canvas can
 * never drift from the chrome. Gold is reserved for the striker, the hub trim
 * and the local player - a seat hue is never gold, so "gold" always reads as
 * "the accent", never as "some other player".
 *
 * ── Still fair, still scales ─────────────────────────────────────────────────
 * Wedges use an affine blend `vis = minFrac + remainder × realShare`, so a whale
 * can never hide an underdog (order preserved: bigger stake is still bigger) and
 * a 1% fighter keeps a visible ~9% wedge. Beyond [MAX_ARCS] the long tail folds
 * into one animated "horde" wedge (`+N`). The engine still decides the winner
 * from the real stakes; the client is told the winning seat and strikes there -
 * the presentation is decoupled from the math on purpose.
 */

const TWO_PI = Math.PI * 2
const TOP = -Math.PI / 2 // striker rest direction (straight up)
const MAX_ARCS = 40

/** Track geometry as fractions of the outer radius. */
const R_INNER = 0.44 // hub edge / inner track edge
const R_RING = 0.72 // where the fighters stand

/** Players who ask for reduced motion still get the result, just not the ride:
 *  no camera shake and no idle drift. The striker still travels - that is the
 *  outcome being reported, not decoration. */
const REDUCED_MOTION =
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true

/** Resolved once per frame-batch; `token()` caches, so this is cheap. */
function palette() {
  return {
    seats: [
      token('--seat-1', 0xff5b6a),
      token('--seat-2', 0xffa23f),
      token('--seat-3', 0x9b7bff),
      token('--seat-4', 0x35d07f),
      token('--seat-5', 0x18c8d4),
      token('--seat-6', 0x4a90ff),
      token('--seat-7', 0xff6fb3),
      token('--seat-8', 0xc056ff),
    ],
    horde: token('--seat-horde', 0x39465a),
    gold: token('--c-gold', 0xffc53d),
    ember: token('--c-lose', 0xff5a65),
    bg: token('--c-bg', 0x0b1017),
    floor: token('--c-bg-2', 0x0f1620),
    bezel: token('--c-surface-2', 0x1d2836),
    hub: token('--c-surface', 0x151e2a),
    text: token('--c-text', 0xedf2f7),
  }
}

interface Segment {
  orders: number[]
  a0: number
  a1: number
  color: number
  label: string
  isHorde: boolean
  cum: number
  you: boolean
}

/** A fighter avatar living on the rotating ring (kept upright by counter-rotation). */
interface Fighter {
  node: Container
  ring: Graphics
  order: number
  angle: number
  r: number
  hit: number // 0..1 recoil energy, decays
}

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  life: number
  max: number
  color: number
  size: number
  gravity: number
}

interface SpinCallbacks {
  onNumber?: (n: number) => void
  onClack?: () => void
  onLanded?: () => void
}

/**
 * The engine's avatar proxy endpoint. VITE_AVATAR_PROXY_URL overrides it (e.g.
 * `/api/v1/avatar` when the page origin proxies the engine); otherwise the page's
 * first host label is swapped for `api` (game.example.com -> api.example.com).
 */
function avatarProxyUrl(): string {
  const configured = import.meta.env.VITE_AVATAR_PROXY_URL
  if (configured) return configured
  const apiHost = window.location.hostname.replace(/^[^.]+/, 'api')
  return `${window.location.protocol}//${apiHost}/api/v1/avatar`
}

/**
 * Telegram avatar URLs (t.me/i/userpic/…) 302-redirect to a CDN with no CORS header,
 * so WebGL can't texture them directly - route them through the engine's avatar
 * proxy (/api/v1/avatar), which re-serves them with CORS. Non-Telegram URLs are used
 * as-is.
 */
function proxied(url: string): string {
  try {
    const u = new URL(url)
    if (!/(^|\.)t\.me$|telegram/.test(u.hostname)) return url
    return `${avatarProxyUrl()}?u=${encodeURIComponent(url)}`
  } catch {
    return url
  }
}

const texCache = new Map<string, Promise<Texture | null>>()
function loadAvatar(url: string): Promise<Texture | null> {
  let p = texCache.get(url)
  if (!p) {
    // Load via an <img> (crossOrigin) rather than Assets.load so the proxied,
    // CORS-enabled bytes yield an untainted texture WebGL will accept (and the
    // browser rasterizes SVG avatars for us).
    p = new Promise<Texture | null>((resolve) => {
      const img = new Image()
      img.crossOrigin = 'anonymous'
      img.onload = () => {
        try {
          resolve(Texture.from(img))
        } catch {
          resolve(null)
        }
      }
      img.onerror = () => resolve(null)
      img.src = proxied(url)
    })
    texCache.set(url, p)
  }
  return p
}

export class PvpScene {
  private app: Application | null = null
  private floorG = new Graphics() // arena floor + territories (rotates)
  private winnerG = new Graphics() // winning-territory flash (rotates)
  private wheel = new Container() // territories + fighters (rotates)
  private fighters: Fighter[] = []
  private fixed = new Container() // bezel / striker / charge ring (no rotation)
  private glowG = new Graphics()
  private chargeG = new Graphics()
  private fxG = new Graphics() // particles (no rotation)

  private seats: Seat[] = []
  private youOrder: number | null = null
  private segments: Segment[] = []

  private cx = 0
  private cy = 0
  private radius = 0

  private rotation = 0
  private idle = true
  private spinning = false
  private spinFrom = 0
  private spinTo = 0
  private spinStart = 0
  private spinDur = 0
  private spinNumber = 0
  private lastStruck = -1
  private cb: SpinCallbacks = {}

  private charge: number | null = null
  private shake = 0
  private particles: Particle[] = []
  /** Index of the landed territory, and the 1..0 energy of its flash. */
  private wonIdx = -1
  private wonEnergy = 0

  async init(host: HTMLElement): Promise<void> {
    const app = new Application()
    await app.init({
      backgroundAlpha: 0,
      antialias: true,
      resolution: window.devicePixelRatio || 1,
      autoDensity: true,
      width: host.clientWidth || 320,
      height: host.clientHeight || 320,
    })
    this.app = app
    host.appendChild(app.canvas)
    this.wheel.addChild(this.floorG, this.winnerG)
    app.stage.addChild(this.glowG, this.wheel, this.fixed, this.fxG)
    app.ticker.add(this.tick)
  }

  destroy(): void {
    this.app?.ticker.remove(this.tick)
    this.app?.destroy(true, { children: true })
    this.app = null
  }

  resize(w: number, h: number): void {
    if (!this.app || w === 0 || h === 0) return
    this.app.renderer.resize(w, h)
    this.cx = w / 2
    this.cy = h / 2
    // 0.82 leaves a band of the table pit visible around the arena, so the wheel
    // reads as an object seated in the environment instead of a disc that fills
    // the whole viewport. The old `-24` sizing was what read as "too massive".
    this.radius = (Math.min(w, h) / 2 - 24) * 0.82
    this.wheel.position.set(this.cx, this.cy)
    this.glowG.position.set(this.cx, this.cy)
    this.draw()
    this.drawFixed()
  }

  setRoster(seats: Seat[]): void {
    this.seats = seats
    this.draw()
  }

  setYou(order: number | null): void {
    this.youOrder = order
    this.draw()
  }

  setIdle(on: boolean): void {
    this.idle = on
    if (on) {
      this.wonIdx = -1
      this.wonEnergy = 0
    }
  }

  setCharge(p: number | null): void {
    this.charge = p == null ? null : Math.min(1, Math.max(0, p))
  }

  get isSpinning(): boolean {
    return this.spinning
  }

  /** Launch the striker and slam it onto [winnerOrder]'s territory. */
  spin(winnerOrder: number, winningNumber: number, durationMs: number, cb: SpinCallbacks = {}): void {
    const seg = this.segments.find((s) => s.orders.includes(winnerOrder)) ?? this.segments[0]
    const mid = seg ? (seg.a0 + seg.a1) / 2 : TOP
    const norm = (x: number) => ((x % TWO_PI) + TWO_PI) % TWO_PI
    const TURNS = 6
    const target = TOP - mid
    const delta = TURNS * TWO_PI + norm(target - this.rotation)

    this.cb = cb
    this.spinNumber = winningNumber
    this.idle = false
    this.charge = null
    this.spinning = true
    this.spinFrom = this.rotation
    this.spinTo = this.rotation + delta
    this.spinStart = performance.now()
    this.spinDur = durationMs
    this.lastStruck = -1
    this.wonIdx = -1
    this.wonEnergy = 0
  }

  // ── layout ──────────────────────────────────────────────────────────────────

  private buildSegments(): Segment[] {
    const total = this.seats.reduce((a, s) => a + s.stake, 0)
    if (total <= 0) return []
    const c = palette()

    const sorted = [...this.seats].sort((a, b) => b.stake - a.stake)
    type Entry = { stake: number; orders: number[]; color: number; label: string; isHorde: boolean; you: boolean }
    const entries: Entry[] = []

    const champions = sorted.length <= MAX_ARCS ? sorted : sorted.slice(0, MAX_ARCS - 1)
    for (const s of champions) {
      entries.push({
        stake: s.stake,
        orders: [s.order],
        color: c.seats[s.order % c.seats.length],
        label: (s.username[0] ?? '?').toUpperCase(),
        isHorde: false,
        you: s.order === this.youOrder,
      })
    }
    if (sorted.length > MAX_ARCS) {
      const tail = sorted.slice(MAX_ARCS - 1)
      entries.push({
        stake: tail.reduce((a, s) => a + s.stake, 0),
        orders: tail.map((s) => s.order),
        color: c.horde,
        label: `+${tail.length}`,
        isHorde: true,
        you: this.youOrder != null && tail.some((s) => s.order === this.youOrder),
      })
    }

    const pot = entries.reduce((a, e) => a + e.stake, 0)
    const segCount = entries.length
    const minFrac = Math.min(0.08, 0.6 / segCount)
    const remainder = 1 - minFrac * segCount

    const out: Segment[] = []
    let a0 = TOP
    let cum = 0
    for (const e of entries) {
      const vis = minFrac + remainder * (e.stake / pot)
      const a1 = a0 + vis * TWO_PI
      cum += vis
      out.push({ orders: e.orders, a0, a1, color: e.color, label: e.label, isHorde: e.isHorde, cum, you: e.you })
      a0 = a1
    }
    return out
  }

  /** Donut wedge between [a0,a1] over the territory track. */
  private wedge(g: Graphics, a0: number, a1: number, rIn: number, rOut: number): Graphics {
    return g
      .moveTo(Math.cos(a0) * rIn, Math.sin(a0) * rIn)
      .lineTo(Math.cos(a0) * rOut, Math.sin(a0) * rOut)
      .arc(0, 0, rOut, a0, a1)
      .lineTo(Math.cos(a1) * rIn, Math.sin(a1) * rIn)
      .arc(0, 0, rIn, a1, a0, true)
      .closePath()
  }

  private draw(): void {
    if (!this.app || this.radius <= 0) return
    // Fighters are recreated on layout change.
    for (const f of this.fighters) f.node.destroy({ children: true })
    this.fighters = []
    this.floorG.clear()
    this.winnerG.clear()
    this.segments = this.buildSegments()

    const c = palette()
    const R = this.radius
    const Ri = R * R_INNER
    const seatById = new Map(this.seats.map((s) => [s.order, s]))

    // The track well: everything is drawn into this recess, so even an empty
    // arena reads as a machined object rather than a hole in the page.
    this.floorG.circle(0, 0, R).fill({ color: c.floor })
    this.floorG.circle(0, 0, R).stroke({ color: c.bg, width: 3, alpha: 0.9 })

    if (this.segments.length === 0) {
      this.dormantTrack(c.bezel, c.gold)
      this.arenaHub()
      return
    }

    const gap = this.segments.length > 1 ? 0.016 : 0
    for (const seg of this.segments) {
      const a0 = seg.a0 + gap / 2
      const a1 = seg.a1 - gap / 2

      // Base territory, then a radial shade from the inner edge outwards. Pixi
      // has no cheap radial gradient, so it is approximated with thin bands:
      // enough of them and the steps disappear, and the territory reads as a
      // lit surface instead of a flat sheet of colour - which also stops a
      // wedge that covers the whole wheel from being a wall of saturation.
      this.wedge(this.floorG, a0, a1, Ri, R).fill({ color: seg.color })
      const STEPS = 18
      const LIT = 0.82 // where the shadow ends and the rim highlight starts
      for (let i = 0; i < STEPS; i++) {
        const t0 = i / STEPS
        const t1 = (i + 1) / STEPS + 0.004
        const mid = (t0 + t1) / 2
        const dark = mid < LIT
        const alpha = dark ? 0.5 * Math.pow(1 - mid / LIT, 1.5) : 0.2 * ((mid - LIT) / (1 - LIT))
        if (alpha < 0.004) continue
        this.wedge(this.floorG, a0, a1, Ri + (R - Ri) * t0, Ri + (R - Ri) * Math.min(1, t1)).fill({
          color: dark ? 0x000000 : 0xffffff,
          alpha,
        })
      }
      // Hard edges so neighbouring hues never bleed into each other.
      this.wedge(this.floorG, a0, a1, Ri, R).stroke({ color: c.bg, width: 2, alpha: 0.85 })

      if (seg.you) {
        this.wedge(this.floorG, a0, a1, Ri, R).stroke({ color: c.gold, width: 3, alpha: 1 })
      }

      const span = seg.a1 - seg.a0
      if (seg.isHorde) {
        const steps = Math.max(3, Math.round(span / 0.1))
        for (let i = 1; i < steps; i++) {
          const a = a0 + (span * i) / steps
          this.floorG.moveTo(Math.cos(a) * Ri, Math.sin(a) * Ri).lineTo(Math.cos(a) * R, Math.sin(a) * R)
        }
        this.floorG.stroke({ color: 0xffffff, width: 1, alpha: 0.1 })
      }
    }

    this.arenaHub()

    // Fighters stand on the track at their territory's mid-angle.
    for (const seg of this.segments) {
      const span = seg.a1 - seg.a0
      if (span < 0.22) continue
      const mid = (seg.a0 + seg.a1) / 2
      const band = (R - Ri) / 2
      const ar = Math.max(12, Math.min(band * 0.78, R * 0.05 + span * 9))
      const seat = seg.isHorde ? null : seatById.get(seg.orders[0])
      const node = this.makeFighter(seg, seat, ar)
      node.position.set(Math.cos(mid) * R * R_RING, Math.sin(mid) * R * R_RING)
      this.wheel.addChild(node)
      this.fighters.push({
        node,
        ring: node.getChildAt(0) as Graphics,
        order: seg.orders[0],
        angle: mid,
        r: ar,
        hit: 0,
      })
    }
  }

  /** An empty arena still has to look like equipment that is waiting, not a void. */
  private dormantTrack(bezel: number, gold: number): void {
    const R = this.radius
    const Ri = R * R_INNER
    const g = this.floorG
    g.circle(0, 0, R).fill({ color: bezel, alpha: 0.35 })
    g.circle(0, 0, Ri).fill({ color: 0x000000, alpha: 0.35 })

    // 32 empty stalls: the track is divided and every slot is unclaimed.
    const stalls = 32
    for (let i = 0; i < stalls; i++) {
      const a = TOP + (i / stalls) * TWO_PI
      g.moveTo(Math.cos(a) * Ri, Math.sin(a) * Ri).lineTo(Math.cos(a) * R, Math.sin(a) * R)
    }
    g.stroke({ color: 0xffffff, width: 1, alpha: 0.05 })

    g.circle(0, 0, R * 0.82).stroke({ color: 0xffffff, width: 1, alpha: 0.05 })
    g.circle(0, 0, R * 0.62).stroke({ color: 0xffffff, width: 1, alpha: 0.05 })
    g.circle(0, 0, R).stroke({ color: gold, width: 1.5, alpha: 0.16 })
  }

  private makeFighter(seg: Segment, seat: Seat | undefined | null, r: number): Container {
    const c = palette()
    const node = new Container()
    // Seat-coloured battle ring (index 0 - pulsed on strike).
    const ring = new Graphics()
      .circle(0, 0, r + 3)
      .fill({ color: c.bg })
      .circle(0, 0, r + 3)
      .stroke({ color: seg.you ? c.gold : seg.color, width: seg.you ? 4 : 3, alpha: 1 })
    node.addChild(ring)

    // Fallback initial coin under the avatar.
    const coin = new Graphics().circle(0, 0, r).fill({ color: seg.isHorde ? c.hub : this.shade(seg.color) })
    node.addChild(coin)
    const label = new Text({
      text: seg.label,
      style: {
        fontFamily: 'Geist, sans-serif',
        fontSize: seg.isHorde ? r * 0.72 : r * 1.05,
        fontWeight: '700',
        fill: seg.isHorde ? c.gold : c.text,
      },
    })
    label.anchor.set(0.5)
    node.addChild(label)

    // Real avatar, masked to a circle, faded in over the fallback.
    const pic = seat?.profilePic
    if (pic) {
      loadAvatar(pic).then((tex) => {
        if (!tex || node.destroyed) return
        const spr = new Sprite(tex)
        spr.anchor.set(0.5)
        spr.width = r * 2
        spr.height = r * 2
        const mask = new Graphics().circle(0, 0, r).fill({ color: 0xffffff })
        node.addChild(mask, spr)
        spr.mask = mask
        label.visible = false
      })
    }
    return node
  }

  /** The stable arena core (the number / countdown is a DOM overlay on top). */
  private arenaHub(): void {
    const c = palette()
    const R = this.radius
    const inner = R * R_INNER
    const g = this.floorG
    g.circle(0, 0, inner + 5).fill({ color: c.bg })
    g.circle(0, 0, inner).fill({ color: c.hub })
    g.circle(0, 0, inner).stroke({ color: 0xffffff, width: 1, alpha: 0.07 })
    g.circle(0, 0, inner - 6).stroke({ color: c.gold, width: 1.5, alpha: 0.5 })
    // Four gold bolts: they turn the hub into a mounted plate, and because they
    // rotate with the wheel they make idle motion readable at a glance.
    for (let i = 0; i < 4; i++) {
      const a = TOP + (i / 4) * TWO_PI + Math.PI / 4
      g.circle(Math.cos(a) * (inner - 13), Math.sin(a) * (inner - 13), 2.5).fill({ color: c.gold, alpha: 0.55 })
    }
  }

  private shade(color: number): number {
    const r = ((color >> 16) & 0xff) * 0.45
    const g = ((color >> 8) & 0xff) * 0.45
    const b = (color & 0xff) * 0.45
    return (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b)
  }

  private drawFixed(): void {
    this.fixed.removeChildren().forEach((c) => c.destroy({ children: true }))
    this.chargeG = new Graphics()

    const c = palette()
    const R = this.radius
    const { cx, cy } = this

    // Machined bezel: a thick dark ring, a lit top edge and a shadowed inner
    // edge. Three strokes are the cheapest thing that reads as milled metal.
    const rim = new Graphics()
      .circle(cx, cy, R + 10)
      .stroke({ color: c.bezel, width: 16 })
      .circle(cx, cy, R + 17)
      .stroke({ color: 0xffffff, width: 1.5, alpha: 0.1 })
      .circle(cx, cy, R + 3)
      .stroke({ color: 0x000000, width: 2, alpha: 0.55 })
    // Bezel notches - a scale to read the striker's travel against.
    for (let i = 0; i < 24; i++) {
      const a = TOP + (i / 24) * TWO_PI
      rim
        .moveTo(cx + Math.cos(a) * (R + 5), cy + Math.sin(a) * (R + 5))
        .lineTo(cx + Math.cos(a) * (R + 15), cy + Math.sin(a) * (R + 15))
    }
    rim.stroke({ color: 0xffffff, width: 1, alpha: 0.12 })
    // The charge sweep rides on top of the bezel (but under the striker), on the
    // inner edge where it frames the track rather than fighting the notches.
    this.fixed.addChild(rim, this.chargeG)

    // Gold striker: a mounted blade, not a floating triangle. The dark offset
    // copy underneath is the shadow that lifts it off the bezel.
    const tipY = cy - R + 26
    const baseY = cy - R - 18
    const ptr = new Graphics()
      .moveTo(cx - 17, baseY + 3)
      .lineTo(cx + 17, baseY + 3)
      .lineTo(cx, tipY + 3)
      .closePath()
      .fill({ color: 0x000000, alpha: 0.5 })
      .moveTo(cx - 17, baseY)
      .lineTo(cx + 17, baseY)
      .lineTo(cx, tipY)
      .closePath()
      .fill({ color: c.gold })
      .moveTo(cx - 17, baseY)
      .lineTo(cx + 17, baseY)
      .lineTo(cx, tipY)
      .closePath()
      .stroke({ color: c.bg, width: 2 })
    // Bright facet down the centre so the blade has a direction.
    ptr
      .moveTo(cx - 5, baseY + 4)
      .lineTo(cx, tipY - 6)
      .lineTo(cx + 2, baseY + 4)
      .closePath()
      .fill({ color: 0xffffff, alpha: 0.45 })
    // Mount cap: the blade is bolted to the bezel, not floating over it.
    ptr
      .circle(cx, baseY, 7)
      .fill({ color: c.bezel })
      .circle(cx, baseY, 7)
      .stroke({ color: c.gold, width: 2 })
      .circle(cx, baseY, 2)
      .fill({ color: c.gold, alpha: 0.7 })
    this.fixed.addChild(ptr)
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private segmentUnderStriker(): number {
    const norm = (x: number) => ((x % TWO_PI) + TWO_PI) % TWO_PI
    const fracUnder = norm(-this.rotation) / TWO_PI
    return this.segments.findIndex((s) => fracUnder <= s.cum)
  }

  private pointerTip(): { x: number; y: number } {
    return { x: this.cx, y: this.cy - this.radius }
  }

  private spark(count: number, color: number, speed: number): void {
    const { x, y } = this.pointerTip()
    for (let i = 0; i < count; i++) {
      const ang = -Math.PI / 2 + (Math.random() - 0.5) * 2.2
      const v = speed * (0.5 + Math.random())
      this.particles.push({ x, y, vx: Math.cos(ang) * v, vy: Math.sin(ang) * v, life: 0, max: 260 + Math.random() * 260, color, size: 1.5 + Math.random() * 2.5, gravity: 0.02 })
    }
  }

  private burst(color: number): void {
    const gold = palette().gold
    for (let i = 0; i < 70; i++) {
      const ang = Math.random() * TWO_PI
      const v = 2 + Math.random() * 8
      this.particles.push({ x: this.cx, y: this.cy - this.radius, vx: Math.cos(ang) * v, vy: Math.sin(ang) * v - 2, life: 0, max: 500 + Math.random() * 500, color: Math.random() < 0.35 ? gold : color, size: 2 + Math.random() * 3.5, gravity: 0.05 })
    }
  }

  private lerpColor(a: number, b: number, t: number): number {
    const ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff
    const br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff
    return (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t)
  }

  private tick = () => {
    if (!this.app) return
    const now = performance.now()

    if (this.spinning) {
      const t = Math.min(1, (now - this.spinStart) / this.spinDur)
      const eased = 1 - Math.pow(1 - t, 4)
      this.rotation = this.spinFrom + (this.spinTo - this.spinFrom) * eased
      this.wheel.rotation = this.rotation
      this.cb.onNumber?.(this.spinNumber * eased)

      const idx = this.segmentUnderStriker()
      if (idx !== this.lastStruck && idx >= 0) {
        this.lastStruck = idx
        this.cb.onClack?.()
        const seg = this.segments[idx]
        this.spark(t > 0.6 ? 12 : 6, seg ? seg.color : palette().gold, 4 + (1 - t) * 3)
        this.shake = Math.max(this.shake, t > 0.7 ? 6 : 2.5)
        // Recoil the struck fighter.
        const f = this.fighters.find((x) => seg && seg.orders.includes(x.order))
        if (f) f.hit = 1
      }

      if (t >= 1) {
        this.spinning = false
        this.cb.onNumber?.(this.spinNumber)
        const idx = this.segmentUnderStriker()
        const seg = this.segments[idx]
        this.burst(seg ? seg.color : palette().gold)
        this.shake = 18
        this.wonIdx = idx
        this.wonEnergy = 1
        const w = this.fighters.find((x) => seg && seg.orders.includes(x.order))
        if (w) w.hit = 1.6
        this.cb.onLanded?.()
      }
    } else if (this.idle && this.segments.length > 0 && !REDUCED_MOTION) {
      // Idle drift only when there is something to look at, and it accelerates
      // with the countdown charge: the motion itself reports the phase.
      this.rotation += this.charge != null ? 0.006 + 0.02 * this.charge : 0.004
      this.wheel.rotation = this.rotation
    }

    this.updateFighters()
    this.drawWinner()
    this.drawGlow()
    this.drawCharge()
    this.updateParticles()
    this.applyShake()
  }

  private updateFighters(): void {
    for (const f of this.fighters) {
      // Counter-rotate so faces stay upright while their territory orbits.
      f.node.rotation = -this.rotation
      const pop = 1 + f.hit * 0.5
      f.node.scale.set(pop)
      if (f.hit > 0) f.hit = Math.max(0, f.hit - 0.06)
    }
  }

  /** The landing has to be unmistakable: the winning territory keeps burning
   *  for a beat after the striker stops, so the eye can find it. */
  private drawWinner(): void {
    const g = this.winnerG
    g.clear()
    if (this.wonIdx < 0 || this.wonEnergy <= 0) return
    const seg = this.segments[this.wonIdx]
    if (!seg) return
    const c = palette()
    const R = this.radius
    const Ri = R * R_INNER
    const e = this.wonEnergy
    // Two-beat flash: a white wash that dies fast, then a steady gold frame.
    this.wedge(g, seg.a0, seg.a1, Ri, R).fill({ color: 0xffffff, alpha: 0.55 * Math.pow(e, 3) })
    this.wedge(g, seg.a0, seg.a1, Ri, R).stroke({ color: c.gold, width: 4, alpha: Math.min(1, e * 1.6) })
    this.wedge(g, seg.a0, seg.a1, Ri - 6, R + 6).stroke({ color: c.gold, width: 2, alpha: 0.5 * e })
    this.wonEnergy = Math.max(0, e - 0.006)
  }

  private drawGlow(): void {
    if (this.radius <= 0) return
    const c = palette()
    const g = this.glowG
    g.clear()
    // No idle breathing: the halo only exists when the round is charging or
    // resolving, so its presence is itself the signal.
    const alpha = this.spinning ? 0.42 : this.charge != null ? 0.1 + this.charge * 0.3 : this.wonEnergy > 0 ? 0.4 * this.wonEnergy : 0.08
    const color = this.charge != null ? this.lerpColor(c.gold, c.ember, this.charge) : c.gold
    g.circle(0, 0, this.radius + 20).stroke({ color, width: 18, alpha })
    g.circle(0, 0, this.radius + 34).stroke({ color, width: 8, alpha: alpha * 0.45 })
  }

  private drawCharge(): void {
    const c = palette()
    const g = this.chargeG
    g.clear()
    if (this.charge == null || this.radius <= 0) return
    const color = this.lerpColor(c.gold, c.ember, this.charge)
    const end = TOP + this.charge * TWO_PI
    const rr = this.radius + 2
    g.circle(this.cx, this.cy, rr).stroke({ color: 0x000000, width: 6, alpha: 0.45 })
    // moveTo first: without it the arc inherits the circle's last point and Pixi
    // draws a chord from there across the whole canvas.
    g.moveTo(this.cx + Math.cos(TOP) * rr, this.cy + Math.sin(TOP) * rr)
      .arc(this.cx, this.cy, rr, TOP, end)
      .stroke({ color, width: 6, alpha: 1 })
    // A bright head on the sweep: it is what the eye tracks as time runs out.
    g.circle(this.cx + Math.cos(end) * rr, this.cy + Math.sin(end) * rr, 5).fill({ color })
  }

  private updateParticles(): void {
    const g = this.fxG
    g.clear()
    if (this.particles.length === 0) return
    const next: Particle[] = []
    for (const p of this.particles) {
      p.life += 16
      p.vy += p.gravity
      p.x += p.vx
      p.y += p.vy
      const k = 1 - p.life / p.max
      if (k <= 0) continue
      g.circle(p.x, p.y, p.size * k).fill({ color: p.color, alpha: Math.min(1, k * 1.4) })
      next.push(p)
    }
    this.particles = next.length > 280 ? next.slice(next.length - 280) : next
  }

  private applyShake(): void {
    if (!this.app) return
    if (REDUCED_MOTION) {
      this.shake = 0
      return
    }
    if (this.shake > 0.2) {
      this.app.stage.position.set((Math.random() - 0.5) * this.shake, (Math.random() - 0.5) * this.shake)
      this.shake *= 0.86
    } else if (this.shake !== 0) {
      this.shake = 0
      this.app.stage.position.set(0, 0)
    }
  }
}
