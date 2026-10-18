// Tiny time-based tween manager. The render loop calls `update(dtMs)` every frame;
// `tween()` resolves its Promise when the tween finishes, so game logic can `await`
// an expressive animation sequence instead of juggling callbacks.

export type EaseFn = (t: number) => number

export const Ease = {
  linear: (t: number) => t,
  outCubic: (t: number) => 1 - Math.pow(1 - t, 3),
  inOutCubic: (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
  inOutSine: (t: number) => -(Math.cos(Math.PI * t) - 1) / 2,
  outBack: (t: number) => {
    const c1 = 1.70158
    const c3 = c1 + 1
    return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)
  },
  outElastic: (t: number) => {
    const c4 = (2 * Math.PI) / 3
    return t === 0 ? 0 : t === 1 ? 1 : Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1
  },
}

interface Track {
  elapsed: number
  duration: number
  ease: EaseFn
  from: number
  to: number
  onUpdate: (value: number) => void
  resolve: () => void
}

export class Tweener {
  private tracks: Track[] = []

  /** Animate `value` from→to over `durationMs`, calling `onUpdate` each frame. */
  tween(
    from: number,
    to: number,
    durationMs: number,
    onUpdate: (value: number) => void,
    ease: EaseFn = Ease.inOutCubic,
  ): Promise<void> {
    onUpdate(from)
    return new Promise<void>((resolve) => {
      this.tracks.push({
        elapsed: 0,
        duration: Math.max(1, durationMs),
        ease,
        from,
        to,
        onUpdate,
        resolve,
      })
    })
  }

  /** Resolve after `durationMs` without animating anything. */
  delay(durationMs: number): Promise<void> {
    return this.tween(0, 1, durationMs, () => {}, Ease.linear)
  }

  update(dtMs: number): void {
    if (this.tracks.length === 0) return
    const done: Track[] = []
    for (const tr of this.tracks) {
      tr.elapsed += dtMs
      const t = Math.min(1, tr.elapsed / tr.duration)
      tr.onUpdate(tr.from + (tr.to - tr.from) * tr.ease(t))
      if (t >= 1) done.push(tr)
    }
    if (done.length > 0) {
      this.tracks = this.tracks.filter((tr) => !done.includes(tr))
      for (const tr of done) tr.resolve()
    }
  }

  /** Resolve outstanding promises so awaiting sequences don't hang on teardown. */
  cancelAll(): void {
    const pending = this.tracks
    this.tracks = []
    for (const tr of pending) tr.resolve()
  }
}
