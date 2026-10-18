// Bridges the CSS design tokens into Pixi, which needs numeric colours.
//
// Reading the live custom properties instead of re-declaring hexes here keeps a
// single source of truth: retinting the game is a tokens.css edit, and the board
// can never drift out of sync with the surrounding chrome.

const cache = new Map<string, number>()

/** Resolves a CSS custom property to a 0xRRGGBB int. Falls back to `fallback`
 *  when the property is missing or not a plain hex (e.g. a var() chain). */
export function token(name: string, fallback: number): number {
  const hit = cache.get(name)
  if (hit !== undefined) return hit

  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const m = /^#([0-9a-f]{6})$/i.exec(raw)
  const value = m ? parseInt(m[1], 16) : fallback
  cache.set(name, value)
  return value
}
