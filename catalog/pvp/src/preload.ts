// Single owner of the inline splash (#splash in index.html).
//
// The wheel is drawn procedurally (no textures to fetch), so the splash only
// bridges the gap until the first game state (auth) arrives. The progress bar
// fills on a short timer for feel; a hard timeout guarantees it can never hang.

const SPLASH_TIMEOUT_MS = 8000

let hidden = false

function setProgress(p: number): void {
  const fill = document.getElementById('splash-fill')
  if (fill) fill.style.width = `${Math.round(Math.min(1, Math.max(0, p)) * 100)}%`
}

export function startSplash(): void {
  const t0 = performance.now()
  const tick = () => {
    if (hidden) return
    const p = Math.min(0.8, (performance.now() - t0) / 2200)
    setProgress(p)
    if (p < 0.8) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
  window.setTimeout(forceHideSplash, SPLASH_TIMEOUT_MS)
}

/** Called by GameScreen on the first PvpState (auth succeeded). */
export function markAuthReady(): void {
  setProgress(1)
  hide()
}

/** Hide regardless of progress - used on fatalError, unsupported currency, timeout. */
export function forceHideSplash(): void {
  hide()
}

function hide(): void {
  if (hidden) return
  hidden = true
  const el = document.getElementById('splash')
  if (!el) return
  el.classList.add('is-hidden')
  el.addEventListener('transitionend', () => el.remove(), { once: true })
  window.setTimeout(() => el.remove(), 700)
}
