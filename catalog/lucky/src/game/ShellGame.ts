import * as THREE from 'three'
import { SCALE, SLOT_X } from './theme'
import { ShellScene, CUP_Z } from './ShellScene'
import { Cup } from './Cup'
import { Tweener, Ease, type EaseFn } from './tween'
import * as sound from '../sound'

export type Phase = 'IDLE' | 'REVEAL' | 'SHUFFLE' | 'READY' | 'PICKED' | 'RESOLVE' | 'RESULT'

const SWAPS = 13
const CROSS = 0.5 // how far cups bow toward/away from the camera as they pass

/**
 * Shell-game choreography. The SERVER owns the outcome: which cup holds the ball
 * and whether the pick won. This class only animates — a cosmetic reveal + shuffle
 * (ball hidden so it can't be tracked), then on the server's verdict it teleports
 * the ball to the winning slot and reveals. The player's choice is a physical slot
 * (0/1/2); `onPick(slot)` is relayed to the WebSocket client by the caller.
 */
export class ShellGame {
  phase: Phase = 'IDLE'
  lastResult: boolean | null = null
  onPhase: ((phase: Phase, won: boolean | null) => void) | null = null
  onPick: ((slot: number) => void) | null = null

  private readonly tw = new Tweener()
  private readonly cups: [Cup, Cup, Cup]
  private readonly slotPos: [THREE.Vector3, THREE.Vector3, THREE.Vector3]

  private cupSlot: [number, number, number] = [0, 1, 2] // cup identity → slot it currently occupies
  private chosenSlot = 0 // the slot the player picked (sent to the server)
  private idleEnabled = true
  private elapsed = 0

  // The dog's limbs are built with a non-zero natural rest pose; remember it so
  // gestures animate as offsets from rest and reset restores it exactly.
  private readonly rest = new Map<THREE.Object3D, THREE.Euler>()

  constructor(private readonly scene: ShellScene) {
    this.cups = scene.cups
    this.slotPos = [
      new THREE.Vector3(SLOT_X[0], 0, CUP_Z),
      new THREE.Vector3(SLOT_X[1], 0, CUP_Z),
      new THREE.Vector3(SLOT_X[2], 0, CUP_Z),
    ]
    for (const part of this.dogParts()) this.rest.set(part, part.rotation.clone())
    scene.onFrame((dt) => this.update(dt))
    this.reset()
  }

  private dogParts(): THREE.Object3D[] {
    const s = this.scene.dog
    return [s.head, s.leftEar, s.rightEar, s.leftArm, s.rightArm]
  }

  /** Play the cosmetic round opening: dog shows a ball under a random cup, hides
   *  it, shuffles, then waits for a pick. The shown cup is purely theatrical — the
   *  real ball position comes from the server at resolve. */
  async begin(): Promise<void> {
    if (this.phase === 'REVEAL' || this.phase === 'SHUFFLE' || this.phase === 'PICKED' || this.phase === 'RESOLVE') {
      return
    }
    this.reset()

    this.setPhase('REVEAL')
    this.idleEnabled = false

    // Reset leaves cupSlot = [0,1,2], so identity == slot here.
    const cosmetic = Math.floor(Math.random() * 3)
    const cp = this.slotPos[cosmetic]
    this.scene.ball.object.position.set(cp.x, 0, cp.z)
    this.scene.ball.object.visible = true

    sound.playReveal()
    await Promise.all([
      this.liftCup(cosmetic, SCALE.liftHeight, 360, Ease.outCubic),
      this.gesture(this.scene.dog.head, 0.32, 360),
    ])
    await this.tw.delay(850)
    sound.playCupDown()
    await Promise.all([
      this.liftCup(cosmetic, 0, 300, Ease.inOutCubic),
      this.gesture(this.scene.dog.head, 0, 300),
    ])
    // Hide the ball before shuffling so the player can't track it — the outcome
    // is the server's, applied by teleport at resolve.
    this.scene.ball.object.visible = false

    this.setPhase('SHUFFLE')
    await this.lean(true)
    await this.shuffle()
    await this.lean(false)

    this.setPhase('READY')
    sound.playReady()
    this.idleEnabled = true
  }

  /** Restore straight to READY without re-revealing (used on reconnect into an open round). */
  resume(): void {
    this.reset()
    this.idleEnabled = true
    this.setPhase('READY')
  }

  /** Highlight the hovered cup while waiting for a pick. */
  hover(cupIndex: number | null): void {
    if (this.phase !== 'READY') return
    this.cups.forEach((c, i) => c.setHighlight(i === cupIndex))
  }

  /** Pick by cup identity (raycast hit). Resolves the chosen slot. */
  pick(cupIndex: number): void {
    if (this.phase !== 'READY') return
    this.lockChoice(this.cupSlot[cupIndex])
  }

  /** Pick by table position (slot 0/1/2) — what the 1/2/3 buttons select. */
  pickSlot(slot: number): void {
    if (this.phase !== 'READY') return
    this.lockChoice(slot)
  }

  /** Apply the server verdict: teleport the ball to its real slot and reveal. */
  resolve(won: boolean, winningCup: number): void {
    if (this.phase !== 'PICKED') return
    void this.runResolve(won, winningCup)
  }

  // ── internals ───────────────────────────────────────────────────────────────

  private lockChoice(slot: number): void {
    this.chosenSlot = slot
    this.setPhase('PICKED')
    this.idleEnabled = false
    this.cups.forEach((c) => c.setHighlight(false))
    sound.playPick()
    this.onPick?.(slot)
  }

  private cupAtSlot(slot: number): number {
    return this.cupSlot.indexOf(slot)
  }

  private async runResolve(won: boolean, winningCup: number): Promise<void> {
    this.tw.cancelAll()
    this.setPhase('RESOLVE')
    this.idleEnabled = false

    // Teleport the ball under the winning cup (still down, so it stays hidden).
    const wp = this.slotPos[winningCup]
    this.scene.ball.object.position.set(wp.x, 0, wp.z)
    this.scene.ball.object.scale.setScalar(1)
    this.scene.ball.object.visible = true

    sound.playLift()
    await this.liftCup(this.cupAtSlot(this.chosenSlot), SCALE.liftHeight, 340, Ease.outCubic)
    if (won) {
      sound.playWin()
      await Promise.all([this.ballPulse(), this.celebrate()])
    } else {
      await this.tw.delay(250)
      sound.playLift()
      await this.liftCup(this.cupAtSlot(winningCup), SCALE.liftHeight, 340, Ease.outCubic)
      sound.playLose()
      await this.disappoint()
    }
    this.lastResult = won
    this.setPhase('RESULT')
  }

  /** Return the scene to the idle/WAIT state (cups home, ball hidden, dog at rest). */
  reset(): void {
    this.tw.cancelAll()
    this.cupSlot = [0, 1, 2]
    this.cups.forEach((cup, i) => {
      cup.object.position.set(this.slotPos[i].x, 0, this.slotPos[i].z)
      cup.setHighlight(false)
    })

    this.scene.ball.object.visible = false
    this.scene.ball.object.scale.setScalar(1)

    const s = this.scene.dog
    s.object.position.y = 0
    s.object.rotation.set(0, 0, 0)
    for (const part of this.dogParts()) part.rotation.copy(this.rest.get(part)!)
    this.idleEnabled = true
    this.lastResult = null
    this.setPhase('IDLE')
  }

  private async shuffle(): Promise<void> {
    for (let i = 0; i < SWAPS; i++) {
      const a = Math.floor(Math.random() * 3)
      const b = (a + 1 + Math.floor(Math.random() * 2)) % 3 // a different slot
      const t = SWAPS > 1 ? i / (SWAPS - 1) : 0
      const dur = 400 - Math.sin(t * Math.PI) * 230 // fast in the middle, slow at the ends
      sound.playShuffle(i)
      await this.swap(a, b, dur)
    }
  }

  private async swap(slotA: number, slotB: number, dur: number): Promise<void> {
    const cupA = this.cupSlot.indexOf(slotA)
    const cupB = this.cupSlot.indexOf(slotB)
    await Promise.all([
      this.arc(cupA, this.slotPos[slotA], this.slotPos[slotB], +1, dur),
      this.arc(cupB, this.slotPos[slotB], this.slotPos[slotA], -1, dur),
    ])
    this.cupSlot[cupA] = slotB
    this.cupSlot[cupB] = slotA
  }

  /** Slide a cup between two slots, bowing in z so crossing cups never overlap. */
  private arc(cupIndex: number, from: THREE.Vector3, to: THREE.Vector3, dir: number, dur: number): Promise<void> {
    const obj = this.cups[cupIndex].object
    return this.tw.tween(
      0,
      1,
      dur,
      (t) => {
        obj.position.x = from.x + (to.x - from.x) * t
        obj.position.z = from.z + (to.z - from.z) * t + dir * CROSS * Math.sin(Math.PI * t)
      },
      Ease.inOutSine,
    )
  }

  private liftCup(cupIndex: number, y: number, dur: number, ease: EaseFn): Promise<void> {
    const obj = this.cups[cupIndex].object
    return this.tw.tween(obj.position.y, y, dur, (v) => (obj.position.y = v), ease)
  }

  /** Rotate a dog pivot's pitch (.x) to its rest pose + `deltaX`. */
  private gesture(part: THREE.Object3D, deltaX: number, dur: number, ease: EaseFn = Ease.inOutSine): Promise<void> {
    const restX = this.rest.get(part)!.x
    return this.tw.tween(part.rotation.x, restX + deltaX, dur, (v) => (part.rotation.x = v), ease)
  }

  private posY(obj: THREE.Object3D, to: number, dur: number, ease: EaseFn = Ease.inOutSine): Promise<void> {
    return this.tw.tween(obj.position.y, to, dur, (v) => (obj.position.y = v), ease)
  }

  private lean(on: boolean): Promise<void> {
    const s = this.scene.dog
    return Promise.all([
      this.gesture(s.head, on ? 0.22 : 0, 350),
      this.gesture(s.leftArm, on ? -0.5 : 0, 350),
      this.gesture(s.rightArm, on ? -0.5 : 0, 350),
    ]).then(() => undefined)
  }

  private ballPulse(): Promise<void> {
    const b = this.scene.ball.object
    return this.tw
      .tween(0, 1, 520, (t) => {
        const s = 1 + Math.sin(Math.PI * t) * 0.28
        b.scale.setScalar(s)
      })
      .then(() => {
        b.scale.setScalar(1)
      })
  }

  private async celebrate(): Promise<void> {
    const s = this.scene.dog
    await Promise.all([
      this.gesture(s.leftArm, -1.4, 260, Ease.outBack),
      this.gesture(s.rightArm, -1.4, 260, Ease.outBack),
      this.gesture(s.head, -0.18, 260),
      this.gesture(s.leftEar, -0.3, 260),
      this.gesture(s.rightEar, -0.3, 260),
    ])
    for (let i = 0; i < 2; i++) {
      await this.posY(s.object, 0.4, 170, Ease.outCubic)
      await this.posY(s.object, 0, 160, Ease.outCubic)
    }
  }

  private disappoint(): Promise<void> {
    const s = this.scene.dog
    return Promise.all([
      this.gesture(s.head, 0.5, 420),
      this.gesture(s.leftEar, 0.55, 420),
      this.gesture(s.rightEar, 0.55, 420),
      this.posY(s.object, -0.12, 420),
    ]).then(() => undefined)
  }

  private setPhase(phase: Phase): void {
    this.phase = phase
    this.onPhase?.(phase, this.lastResult)
  }

  private update(dtMs: number): void {
    this.tw.update(dtMs)
    this.elapsed += dtMs
    if (this.idleEnabled) {
      const s = this.elapsed / 1000
      this.scene.dog.object.position.y = Math.sin(s * 1.8) * 0.05
      this.scene.dog.object.rotation.y = Math.sin(s * 0.7) * 0.05
    }
  }
}
