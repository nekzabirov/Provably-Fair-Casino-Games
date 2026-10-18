// Plinko paytable — the exact mirror of the engine's PlinkoMultiplier tables.
// Display + verification only: the engine's copy is authoritative for payouts.
// Tables are symmetric, so only the edge→center half is stored; slot k of an
// n-row board reads half[min(k, n - k)]. Every (risk, rows) table is tuned to
// RTP ≈ 0.97. Generated together with the Kotlin tables; do not hand-edit.

export type Risk = 'LOW' | 'MEDIUM' | 'HIGH'

export const MIN_ROWS = 8
export const MAX_ROWS = 16

const HALF_TABLES: Record<Risk, Record<number, number[]>> = {
  LOW: {
    8: [5.5, 2.1, 1.08, 0.98, 0.48],
    9: [5.5, 1.96, 1.57, 0.98, 0.68],
    10: [8.7, 2.9, 1.37, 1.08, 0.98, 0.49],
    11: [8.2, 2.9, 1.86, 1.27, 0.98, 0.69],
    12: [9.8, 2.9, 1.57, 1.37, 1.08, 0.98, 0.49],
    13: [7.9, 3.9, 2.9, 1.86, 1.18, 0.88, 0.69],
    14: [7, 3.9, 1.86, 1.37, 1.27, 1.08, 0.98, 0.49],
    15: [14.7, 7.8, 2.9, 1.96, 1.47, 1.08, 0.98, 0.69],
    16: [15.7, 8.8, 1.96, 1.37, 1.37, 1.18, 1.08, 0.98, 0.49],
  },
  MEDIUM: {
    8: [12.7, 2.9, 1.27, 0.69, 0.39],
    9: [17.6, 3.9, 1.66, 0.88, 0.49],
    10: [22, 4.9, 1.96, 1.37, 0.59, 0.39],
    11: [24, 5.9, 2.9, 1.76, 0.69, 0.49],
    12: [32, 10.8, 3.9, 1.96, 1.08, 0.59, 0.29],
    13: [42, 12.7, 5.9, 2.9, 1.27, 0.69, 0.39],
    14: [57, 14.7, 6.9, 3.9, 1.86, 0.98, 0.49, 0.2],
    15: [86, 17.6, 10.8, 4.9, 2.9, 1.27, 0.49, 0.3],
    16: [108, 40, 9.8, 4.9, 2.9, 1.47, 0.98, 0.49, 0.29],
  },
  HIGH: {
    8: [28, 3.9, 1.47, 0.29, 0.2],
    9: [42, 6.9, 1.96, 0.59, 0.19],
    10: [74, 9.8, 2.9, 0.88, 0.29, 0.2],
    11: [117, 13.7, 5.1, 1.37, 0.39, 0.19],
    12: [166, 23, 7.9, 1.96, 0.69, 0.2, 0.2],
    13: [255, 36, 10.8, 3.9, 0.98, 0.2, 0.19],
    14: [412, 55, 17.6, 4.9, 1.86, 0.29, 0.2, 0.2],
    15: [607, 81, 26, 7.8, 2.9, 0.49, 0.2, 0.2],
    16: [980, 127, 25, 8.8, 3.9, 1.96, 0.2, 0.2, 0.2],
  },
}

/** The full multiplier row (rows + 1 slots, edge to edge) for a board. */
export function multipliers(risk: Risk, rows: number): number[] {
  const half = HALF_TABLES[risk][rows]
  return Array.from({ length: rows + 1 }, (_, k) => half[Math.min(k, rows - k)])
}

/** The multiplier for one slot. */
export function multiplierFor(risk: Risk, rows: number, slot: number): number {
  return HALF_TABLES[risk][rows][Math.min(slot, rows - slot)]
}

/** Compact label for a slot chip: ×980, ×12.7, ×0.49. */
export function formatMultiplier(m: number): string {
  return m >= 100 ? `${Math.round(m)}` : m >= 10 ? `${Number(m.toFixed(1))}` : `${Number(m.toFixed(2))}`
}
