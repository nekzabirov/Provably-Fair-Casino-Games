import { Application, Container, Graphics, Text, Ticker } from 'pixi.js'
import { formatMultiplier } from '../tables'
import { playLand, playPeg } from '../sound'
import { token } from '../theme'

/**
 * The Pixi board: a triangular peg field with the payout ladder underneath, and
 * gold balls that hop peg-to-peg along a server-decided path.
 *
 * Geometry. For an n-row board peg row r (0-based) holds r+3 pegs. A ball that
 * has bounced right `c` times out of `r` sits exactly on peg `c + 1` of row `r` —
 * so the animation just chains waypoints; no physics simulation, the outcome is
 * the engine's. Several balls can be in flight at once (rapid-fire drops); each
 * carries its own ticker state.
 *
 * Colour comes from the CSS tokens through `theme.ts`, so the canvas cannot
 * drift away from the chrome around it.
 */

interface BallOpts {
  path: number[]
  slot: number
  multiplier: number
  /** Fired the moment the ball settles into its slot. */
  onLand: () => void
}

interface Peg {
  dot: Container
  glow: Graphics
  flash: number
}

interface SlotNode {
  box: Container
  flash: Graphics
  label: Text
  /** Fit scale applied at build time; the hit swell multiplies onto it. */
  labelScale: number
  baseY: number
  labelBaseY: number
  bump: number
  hit: number
}

/** True when the player asked the system to cut animation down. */
function reducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * Where a multiplier sits on the risk ramp: 0 = deep loss, 5 = jackpot.
 * The cuts are chosen so that "you get less back than you staked" is always
 * red or orange and "you double or better" is always green or gold — the
 * player should never have to read the number to know which side it is on.
 */
function payTier(m: number): number {
  if (m < 0.5) return 0
  if (m < 1) return 1
  if (m < 1.6) return 2
  if (m < 3) return 3
  if (m < 20) return 4
  return 5
}

const RAMP = ['--pay-0', '--pay-1', '--pay-2', '--pay-3', '--pay-4', '--pay-5'] as const
const RAMP_FALLBACK = [0xe5484d, 0xf2683c, 0xe9b23c, 0x9bcb3b, 0x2ed47a, 0xffc53d]

/** Blends two 0xRRGGBB ints. */
function mix(a: number, b: number, t: number): number {
  const l = (s: number) => Math.round(((a >> s) & 0xff) + (((b >> s) & 0xff) - ((a >> s) & 0xff)) * t)
  return (l(16) << 16) | (l(8) << 8) | l(0)
}

export class PlinkoScene {
  private app: Application | null = null

  private board = new Container()
  private ballLayer = new Container()

  private rows = 0
  private multipliers: number[] = []
  private pegs = new Map<string, Peg>()
  private slots: SlotNode[] = []

  private viewW = 0
  private viewH = 0
  private centerX = 0
  private topPad = 0
  private gapX = 0
  private gapY = 0
  private pegR = 3
  private ballR = 7

  private liveBalls = 0

  async init(host: HTMLElement): Promise<void> {
    const app = new Application()
    await app.init({
      // Transparent: the depth behind the pegs is a CSS gradient on the host,
      // which costs nothing per frame and gives a smoother falloff than Pixi.
      backgroundAlpha: 0,
      antialias: true,
      resolution: window.devicePixelRatio || 1,
      autoDensity: true,
      width: host.clientWidth || 320,
      height: host.clientHeight || 320,
    })
    this.app = app
    host.appendChild(app.canvas)
    app.stage.addChild(this.board, this.ballLayer)
    app.ticker.add(this.pulseTick)
  }

  destroy(): void {
    this.app?.ticker.remove(this.pulseTick)
    this.app?.destroy(true, { children: true })
    this.app = null
  }

  resize(w: number, h: number): void {
    if (!this.app || w === 0 || h === 0) return
    this.viewW = w
    this.viewH = h
    this.app.renderer.resize(w, h)
    if (this.rows > 0) this.buildBoard(this.rows, this.multipliers)
  }

  /** True while any ball is falling — used to keep the result toast honest. */
  get busy(): boolean {
    return this.liveBalls > 0
  }

  /** (Re)builds pegs + the payout ladder for a board. Clears in-flight balls. */
  buildBoard(rows: number, multipliers: number[]): void {
    if (!this.app || this.viewW === 0) {
      this.rows = rows
      this.multipliers = multipliers
      return
    }
    this.rows = rows
    this.multipliers = multipliers
    this.board.removeChildren().forEach((c) => c.destroy({ children: true }))
    this.ballLayer.removeChildren().forEach((c) => c.destroy({ children: true }))
    this.pegs.clear()
    this.slots = []
    this.liveBalls = 0

    const PAD = 10
    const slotH = Math.min(34, Math.max(26, this.viewH * 0.055))
    // Width must fit the widest peg row (rows+2 pegs) AND rows+1 slot chips.
    this.gapX = Math.min((this.viewW - 2 * PAD) / (rows + 2), (this.viewW - 2 * PAD) / (rows + 1))
    const boardH = this.viewH - 2 * PAD - slotH
    this.gapY = boardH / (rows + 1.35)
    this.centerX = this.viewW / 2
    this.topPad = PAD + this.gapY * 1.15
    this.pegR = Math.max(3, Math.min(6, this.gapX * 0.145))
    this.ballR = Math.max(5, Math.min(11, this.gapX * 0.32))

    this.drawChute()
    this.drawPegs(rows)
    this.drawLadder(rows, multipliers, slotH)
  }

  /** Spawns a ball and animates it along [path]; resolves visually in ~0.2s/row. */
  dropBall(opts: BallOpts): void {
    if (!this.app || this.rows === 0) {
      opts.onLand()
      return
    }
    const { path } = opts
    const n = this.rows

    // Waypoints: entry above the board, the peg hit on every row, then the slot.
    const xs: number[] = [this.centerX]
    const ys: number[] = [this.topPad - this.gapY]
    let c = 0
    for (let r = 0; r < n; r++) {
      xs.push(this.pegX(r, c + 1))
      ys.push(this.pegY(r) - this.pegR - this.ballR * 0.7)
      c += path[r]
    }
    xs.push(this.slotX(opts.slot))
    ys.push(this.slotY() + 2)

    const ballFill = token('--ball', 0xffc53d)
    const ballLit = token('--ball-lit', 0xfff0c2)

    const ball = new Container()
    // A short trail is what makes a 7px ball trackable across a field of pegs;
    // without it the eye loses the ball on a fast board.
    const trail = new Graphics()
    const glow = new Graphics().circle(0, 0, this.ballR * 2.1).fill({ color: ballFill, alpha: 0.35 })
    glow.blendMode = 'add'
    const body = new Graphics()
      .circle(0, 0, this.ballR)
      .fill({ color: ballFill })
      .circle(-this.ballR * 0.3, -this.ballR * 0.32, this.ballR * 0.42)
      .fill({ color: ballLit, alpha: 0.95 })
    ball.addChild(glow, body)
    ball.position.set(xs[0], ys[0])
    this.ballLayer.addChild(trail, ball)
    this.liveBalls++

    // Reduced motion still needs the ball to show where it lands, so the drop
    // stays but runs short and drops the trail.
    // One tap = one drop and the player taps a lot, so the fall is kept short:
    // long enough to follow the ball peg to peg, short enough that the next tap
    // never feels queued behind an animation.
    const calm = reducedMotion()
    const SEG_MS = calm ? 80 : 145
    const segments = xs.length - 1
    const history: { x: number; y: number }[] = []
    let elapsed = 0
    let lastSeg = -1

    const step = (ticker: Ticker) => {
      elapsed += ticker.deltaMS
      const total = Math.min(elapsed / SEG_MS, segments)
      const seg = Math.min(Math.floor(total), segments - 1)
      const u = total - seg

      if (seg !== lastSeg && seg > 0 && seg <= n) {
        // Entered a new segment ⇒ the previous waypoint (a peg) was just hit.
        this.flashPeg(seg - 1, this.cumRight(path, seg - 1) + 1)
        playPeg(seg)
      }
      lastSeg = seg

      // Horizontal: ease toward the next column. Vertical: gravity fall with an
      // arc over the peg (rise a touch, then drop).
      const x = xs[seg] + (xs[seg + 1] - xs[seg]) * u
      const fall = u * u
      const hop = Math.sin(u * Math.PI) * this.gapY * 0.22
      const y = ys[seg] + (ys[seg + 1] - ys[seg]) * fall - hop
      ball.position.set(x, y)

      history.unshift({ x, y })
      if (history.length > 14) history.pop()
      trail.clear()
      // Every third sample: a streak the eye can follow, not a smear that turns
      // the ball into a blob.
      for (let i = calm ? history.length : 3; i < history.length; i += 3) {
        const p = history[i]
        const k = 1 - i / history.length
        trail.circle(p.x, p.y, this.ballR * (0.2 + k * 0.45)).fill({ color: ballFill, alpha: k * 0.3 })
      }

      if (total >= segments) {
        this.app?.ticker.remove(step)
        this.landBall(ball, trail, opts)
      }
    }
    this.app.ticker.add(step)
  }

  // ── drawing ─────────────────────────────────────────────────────────────────

  /** The lip the ball falls out of. Gives the drop a physical origin. */
  private drawChute(): void {
    const line = token('--peg-shade', 0x35485c)
    const w = this.gapX * 1.6
    const h = Math.max(7, this.gapY * 0.26)
    const y = Math.max(3, this.topPad - this.gapY * 0.95 - h / 2)
    const chute = new Graphics()
      .roundRect(this.centerX - w / 2, y, w, h, h / 2)
      .fill({ color: line, alpha: 0.7 })
      .roundRect(this.centerX - w / 2, y, w, h * 0.5, h / 2)
      .fill({ color: 0xffffff, alpha: 0.06 })
    this.board.addChild(chute)
  }

  private drawPegs(rows: number): void {
    const face = token('--peg', 0x6e8aa8)
    const shade = token('--peg-shade', 0x35485c)
    const lit = token('--peg-lit', 0xeaf4ff)
    const r = this.pegR

    for (let row = 0; row < rows; row++) {
      for (let p = 0; p < row + 3; p++) {
        const x = this.pegX(row, p)
        const y = this.pegY(row)

        const glow = new Graphics().circle(0, 0, r * 3).fill({ color: lit, alpha: 0.45 })
        glow.position.set(x, y)
        glow.blendMode = 'add'
        glow.alpha = 0

        // Three passes read as a sphere sitting on the board rather than a flat
        // dot: a contact shadow below, the body, then a top-left specular.
        const dot = new Container()
        const g = new Graphics()
          .circle(0, r * 0.42, r * 1.02)
          .fill({ color: shade, alpha: 0.7 })
          .circle(0, 0, r)
          .fill({ color: face })
          .circle(-r * 0.3, -r * 0.34, r * 0.46)
          .fill({ color: lit, alpha: 0.75 })
        dot.addChild(g)
        dot.position.set(x, y)

        this.board.addChild(glow, dot)
        this.pegs.set(`${row}:${p}`, { dot, glow, flash: 0 })
      }
    }
  }

  /**
   * The payout ladder. This is the whole paytable and the most important thing
   * on screen, so it is built to be read at a glance: a saturated fill from the
   * risk ramp carries the meaning, dark ink carries the number, and the chip
   * gets taller the more it pays so the curve has a visible silhouette.
   */
  private drawLadder(rows: number, multipliers: number[], slotH: number): void {
    const ink = token('--pay-ink', 0x06111b)
    const slotY = this.slotY()
    const chipW = this.gapX * 0.94
    const radius = Math.min(7, chipW * 0.24)

    for (let i = 0; i <= rows; i++) {
      const m = multipliers[i]
      const tier = payTier(m)
      const fill = token(RAMP[tier], RAMP_FALLBACK[tier])
      const x = this.slotX(i)
      // Bottoms align, tops step up toward the edges: the silhouette of the
      // chip row is the shape of the paytable.
      const chipH = slotH * (0.78 + tier * 0.044)
      const top = slotH - chipH

      const box = new Container()
      const g = new Graphics()
        .roundRect(-chipW / 2, top + 2, chipW, chipH, radius)
        .fill({ color: mix(fill, 0x000000, 0.45) })
        .roundRect(-chipW / 2, top, chipW, chipH, radius)
        .fill({ color: fill })
        .roundRect(-chipW / 2, top, chipW, chipH * 0.5, radius)
        .fill({ color: 0xffffff, alpha: 0.14 })
      box.addChild(g)
      box.position.set(x, slotY)

      const flash = new Graphics()
        .roundRect(-chipW / 2, top, chipW, chipH, radius)
        .fill({ color: 0xffffff })
      flash.position.set(x, slotY)
      flash.alpha = 0
      flash.blendMode = 'add'

      // The multiplier sign only fits on wide boards; on a 16-row board the
      // digits themselves are worth more pixels than the symbol.
      const wide = chipW >= 34
      const label = new Text({
        text: `${wide ? '×' : ''}${formatMultiplier(m)}`,
        style: {
          fontFamily: 'Geist, sans-serif',
          fontSize: Math.max(10, Math.min(16, chipW * 0.44)),
          fontWeight: '700',
          fill: ink,
          align: 'center',
        },
      })
      label.anchor.set(0.5)
      // Long values (0.29, 10.8) can still overrun a narrow chip; shrink to fit
      // rather than clip, so no chip is ever unreadable.
      const maxW = chipW - 5
      const labelScale = label.width > maxW ? maxW / label.width : 1
      label.scale.set(labelScale)
      const labelY = slotY + top + chipH / 2
      label.position.set(x, labelY)

      this.board.addChild(box, flash, label)
      this.slots.push({ box, flash, label, labelScale, baseY: slotY, labelBaseY: labelY, bump: 0, hit: 0 })
    }
  }

  // ── internals ───────────────────────────────────────────────────────────────

  private cumRight(path: number[], upToRow: number): number {
    let c = 0
    for (let i = 0; i < upToRow; i++) c += path[i]
    return c
  }

  private pegX(row: number, p: number): number {
    return this.centerX + (p - (row + 2) / 2) * this.gapX
  }

  private pegY(row: number): number {
    return this.topPad + row * this.gapY
  }

  private slotX(i: number): number {
    return this.centerX + (i - this.rows / 2) * this.gapX
  }

  private slotY(): number {
    return this.topPad + this.rows * this.gapY + this.gapY * 0.25
  }

  private flashPeg(row: number, p: number): void {
    const peg = this.pegs.get(`${row}:${p}`)
    if (peg) peg.flash = 1
  }

  private landBall(ball: Container, trail: Graphics, opts: BallOpts): void {
    playLand(opts.multiplier)
    opts.onLand()
    const slot = this.slots[opts.slot]
    if (slot) {
      slot.bump = 1
      slot.hit = 1
    }

    // Squash, fade, remove.
    let t = 0
    const fade = (ticker: Ticker) => {
      t += ticker.deltaMS / 260
      ball.scale.set(1 + t * 0.25, Math.max(0.2, 1 - t * 0.7))
      ball.alpha = Math.max(0, 1 - t)
      trail.alpha = Math.max(0, 1 - t * 2)
      if (t >= 1) {
        this.app?.ticker.remove(fade)
        ball.destroy({ children: true })
        trail.destroy()
        this.liveBalls = Math.max(0, this.liveBalls - 1)
      }
    }
    this.app?.ticker.add(fade)
  }

  /** One shared ticker drives peg flash decay and slot bumps — O(active) per frame. */
  private pulseTick = (ticker: Ticker) => {
    const dt = ticker.deltaMS / 1000
    this.pegs.forEach((peg) => {
      if (peg.flash <= 0) return
      peg.flash = Math.max(0, peg.flash - dt * 3.2)
      peg.glow.alpha = peg.flash * 0.9
      const s = 1 + peg.flash * 0.45
      peg.dot.scale.set(s)
    })
    this.slots.forEach((slot) => {
      if (slot.bump <= 0 && slot.hit <= 0) return
      slot.bump = Math.max(0, slot.bump - dt * 3.5)
      slot.hit = Math.max(0, slot.hit - dt * 1.9)
      const dy = Math.sin(slot.bump * Math.PI) * 6
      slot.box.y = slot.baseY + dy
      slot.flash.y = slot.baseY + dy
      slot.label.y = slot.labelBaseY + dy
      // The chip the ball landed in blows out to white and swells slightly, so
      // the result is legible even at the edge of a 17-chip row.
      slot.flash.alpha = slot.hit * 0.45
      const s = 1 + slot.hit * 0.16
      slot.box.scale.set(s)
      slot.flash.scale.set(s)
      slot.label.scale.set(slot.labelScale * s)
    })
  }
}
