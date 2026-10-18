// Persists the player's last bet (per currency), rows and risk so the game opens
// with their previous choices instead of re-asking. Storage failures degrade
// silently to the supplied fallback (blocked/private-mode webviews).

import { MAX_ROWS, MIN_ROWS, type Risk } from './tables'

const BET_KEY = 'plinko.bet' // JSON map: { [currency]: number }
const ROWS_KEY = 'plinko.rows'
const RISK_KEY = 'plinko.risk'

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

export function loadRows(fallback: number): number {
  try {
    const raw = localStorage.getItem(ROWS_KEY)
    const n = raw ? parseInt(raw, 10) : NaN
    return Number.isFinite(n) && n >= MIN_ROWS && n <= MAX_ROWS ? n : fallback
  } catch {
    return fallback
  }
}

export function saveRows(value: number): void {
  try {
    localStorage.setItem(ROWS_KEY, String(value))
  } catch {
    /* ignore */
  }
}

export function loadRisk(fallback: Risk): Risk {
  try {
    const raw = localStorage.getItem(RISK_KEY)
    return raw === 'LOW' || raw === 'MEDIUM' || raw === 'HIGH' ? raw : fallback
  } catch {
    return fallback
  }
}

export function saveRisk(value: Risk): void {
  try {
    localStorage.setItem(RISK_KEY, value)
  } catch {
    /* ignore */
  }
}
