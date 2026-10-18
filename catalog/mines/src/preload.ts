// Single owner of asset preloading + the inline splash (#splash in index.html).
//
// The splash is painted by index.html before this bundle parses, so the player
// never sees a blank screen. This module loads the heavy assets in parallel,
// drives the splash progress bar with real completion, and removes the splash
// only once BOTH the assets are ready AND the first game state (auth) has arrived.
// A hard timeout guarantees the splash can never hang.

import { Assets } from 'pixi.js'

const SPLASH_TIMEOUT_MS = 8000

/** The two sprites drawn on the board. Cells themselves are vector-drawn from
 *  the design tokens, so their contrast survives any future palette change. */
export const CELL_ALIASES = ['gem', 'mine'] as const

let assetsDone = false
let authDone = false
let hidden = false
let registered = false

let resolveAssets!: () => void
const assetsReady = new Promise<void>((r) => (resolveAssets = r))

/** Registers the board sprites so the Pixi cache is shared with GameBoard.
 *  Idempotent — safe to call from both preload and GameBoard. */
export function registerGameAssets(): void {
  if (registered) return
  registered = true
  for (const alias of CELL_ALIASES) {
    Assets.add({ alias, src: `${alias}.webp` })
  }
}

function setProgress(p: number): void {
  const fill = document.getElementById('splash-fill')
  if (fill) fill.style.width = `${Math.round(Math.min(1, Math.max(0, p)) * 100)}%`
}

/** Loads a plain image (the CSS backdrop, which Pixi does not manage). */
function loadImage(url: string): Promise<void> {
  return new Promise((resolve) => {
    const img = new Image()
    img.onload = img.onerror = () => resolve()
    img.src = url
  })
}

export function startPreload(): void {
  registerGameAssets()

  // Sprites are the heaviest — give them the first 70% of the bar.
  const texP = Assets.load([...CELL_ALIASES], (frac) => setProgress(frac * 0.7)).then(() =>
    setProgress(0.7),
  )
  const bgP = loadImage('/bg.webp').then(() => setProgress(1))

  Promise.all([texP, bgP]).then(() => {
    setProgress(1)
    assetsDone = true
    resolveAssets()
    maybeHide()
  })

  // Safety net: never let the splash hang if assets or auth stall.
  window.setTimeout(forceHideSplash, SPLASH_TIMEOUT_MS)
}

/** Resolves once every preloaded asset is in the cache. */
export function onAssetsReady(): Promise<void> {
  return assetsReady
}

/** Called by GameScreen on the first MinesState (auth succeeded). */
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
