// Single owner of the inline splash (#splash in index.html).
//
// The splash is painted by index.html before this bundle parses, so the player
// never sees a blank screen. TeleFly draws its scene with Pixi Graphics (no
// texture assets), so the only preloaded asset is the logo. The splash is removed
// only once BOTH the logo is ready AND the first game state (auth) has arrived.
// A hard timeout guarantees the splash can never hang.

const SPLASH_TIMEOUT_MS = 8000

let assetsDone = false
let authDone = false
let hidden = false

function setProgress(p: number): void {
  const fill = document.getElementById('splash-fill')
  if (fill) fill.style.width = `${Math.round(Math.min(1, Math.max(0, p)) * 100)}%`
}

function loadImage(url: string): Promise<void> {
  return new Promise((resolve) => {
    const img = new Image()
    img.onload = img.onerror = () => resolve()
    img.src = url
  })
}

export function startPreload(): void {
  setProgress(0.35)
  loadImage('/logo.svg').then(() => {
    setProgress(1)
    assetsDone = true
    maybeHide()
  })
  // Safety net: never let the splash hang if the logo or auth stall.
  window.setTimeout(forceHideSplash, SPLASH_TIMEOUT_MS)
}

/** Called by GameScreen on the first server state (auth succeeded). */
export function markAuthReady(): void {
  authDone = true
  maybeHide()
}

/** Hide regardless of progress — used on fatalError, unsupported currency, timeout. */
export function forceHideSplash(): void {
  hide()
}

function maybeHide(): void {
  if (assetsDone && authDone) hide()
}

function hide(): void {
  if (hidden) return
  hidden = true
  const el = document.getElementById('splash')
  if (!el) return
  el.classList.add('is-hidden')
  el.addEventListener('transitionend', () => el.remove(), { once: true })
  // Fallback if transitionend doesn't fire (backgrounded tab, display swap).
  window.setTimeout(() => el.remove(), 700)
}
