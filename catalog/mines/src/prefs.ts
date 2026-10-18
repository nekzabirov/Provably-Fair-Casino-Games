// Persists the player's last bet (per currency) and mine count so the game opens
// with their previous choices instead of re-asking. Storage failures degrade
// silently to the supplied fallback (blocked/private-mode webviews).

const BET_KEY = 'mines.bet' // JSON map: { [currency]: number }
const MINES_KEY = 'mines.mineCount'

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

export function loadBet(currency: string, fallback: number): number {
  const map = readJson<Record<string, number>>(BET_KEY, {})
  const v = map[currency]
  return typeof v === 'number' && v > 0 ? v : fallback
}

export function saveBet(currency: string, value: number): void {
  try {
    const map = readJson<Record<string, number>>(BET_KEY, {})
    map[currency] = value
    localStorage.setItem(BET_KEY, JSON.stringify(map))
  } catch {
    /* ignore */
  }
}

export function loadMineCount(fallback: number): number {
  try {
    const raw = localStorage.getItem(MINES_KEY)
    const n = raw ? parseInt(raw, 10) : NaN
    return Number.isFinite(n) && n >= 1 && n <= 24 ? n : fallback
  } catch {
    return fallback
  }
}

export function saveMineCount(value: number): void {
  try {
    localStorage.setItem(MINES_KEY, String(value))
  } catch {
    /* ignore */
  }
}
