// Client-side mirror of the engine's MinesMultiplier, used only to show the
// player what the NEXT safe pick would be worth. The engine stays authoritative
// for every settled amount; nothing here is ever sent back.

const GRID_CELLS = 25
const RTP = 0.97

/**
 * Cumulative multiplier after `opened` safe picks:
 * RTP * product over k of (cells - k) / (cells - mines - k).
 */
export function multiplierAt(mines: number, opened: number): number {
  const safe = GRID_CELLS - mines
  if (opened <= 0 || opened > safe) return opened === 0 ? 1 : 0
  let m = RTP
  for (let k = 0; k < opened; k++) {
    m *= (GRID_CELLS - k) / (safe - k)
  }
  return m
}

/**
 * The multiplier one more safe pick would reach, or null when the formula does
 * not reproduce what the engine already sent.
 *
 * `serverCurrent` is the cumulative multiplier the engine reported for the last
 * revealed cell. If our reconstruction disagrees with it the paytable has moved
 * server-side, and showing a stale projection next to real money would be worse
 * than showing nothing — so we show nothing.
 */
export function nextMultiplier(
  mines: number,
  opened: number,
  serverCurrent: number | null,
): number | null {
  const safe = GRID_CELLS - mines
  if (opened >= safe) return null

  if (serverCurrent != null) {
    const reconstructed = multiplierAt(mines, opened)
    if (Math.abs(reconstructed - serverCurrent) > 0.005) return null
  }
  return multiplierAt(mines, opened + 1)
}
