// Display helpers. Pool/price are TON nano (10^9 minor units).

const NANO = 1_000_000_000

/** nano -> whole TON, grouped (e.g. 12850 -> "12 850"). */
export function formatPool(nano: number): string {
  return Math.round(nano / NANO)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ' ')
}

/** nano -> TON with up to 2 decimals, trailing zeros trimmed (e.g. 0.5). */
export function formatTon(nano: number): string {
  const ton = nano / NANO
  return ton.toFixed(2).replace(/\.?0+$/, '')
}

/** Whole-ish TON amount -> compact display ("10", "9 240", "19.5M"). System-ticket
 *  prices span seven orders of magnitude, so big values compress instead of wrapping. */
export function formatTonAmount(ton: number): string {
  if (ton >= 1_000_000) {
    const m = ton / 1_000_000
    return `${m >= 10 ? Math.round(m) : Math.round(m * 10) / 10}M`
  }
  if (ton >= 1000) return formatCount(ton)
  return ton.toFixed(2).replace(/\.?0+$/, '')
}

/** Integer count with thin group spaces (1947792 -> "1 947 792"). */
export function formatCount(n: number): string {
  return Math.round(n)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ' ')
}

/** Two-digit ball label (1 -> "01", 49 -> "49"). */
export function pad2(n: number): string {
  return n.toString().padStart(2, '0')
}

/** winChance (0..1) -> percent string. Tiny odds round to two decimals so they
 *  never collapse to a flat "0%" once a player holds tickets. */
export function formatChance(chance: number): string {
  if (chance <= 0) return '0%'
  const pct = chance * 100
  if (pct >= 1) return `${Math.round(pct)}%`
  if (pct >= 0.01) return `${pct.toFixed(2)}%`
  return '<0.01%'
}

/** winChance (0..1) -> real-lotto odds ("1 in 15 134", compacting huge
 *  denominators: "1 in 13.98M", "1 in 66.6K"). Percent form repels below ~0.1%;
 *  "1 in N" is how lotteries state the same number attractively and honestly. */
export function formatOdds(chance: number): string {
  if (chance <= 0) return '—'
  const d = Math.round(1 / chance)
  if (d >= 1_000_000) return `1 in ${(d / 1_000_000).toFixed(2)}M`
  if (d >= 100_000) return `1 in ${Math.round(d / 1000)}K`
  if (d >= 10_000) return `1 in ${(d / 1000).toFixed(1)}K`
  return `1 in ${formatCount(d)}`
}

/** ms-until-draw -> "Dd HH:MM:SS" / "HH:MM:SS". */
export function formatCountdown(endsAt: number, now: number): string {
  let s = Math.max(0, Math.floor((endsAt - now) / 1000))
  const d = Math.floor(s / 86400)
  s -= d * 86400
  const h = Math.floor(s / 3600)
  s -= h * 3600
  const m = Math.floor(s / 60)
  s -= m * 60
  const p = (n: number) => n.toString().padStart(2, '0')
  return (d > 0 ? `${d}d ` : '') + `${p(h)}:${p(m)}:${p(s)}`
}

/** ms-until-draw -> the timer-bar face "DD:HH:MM:SS" (always four fields). */
export function formatTimer(endsAt: number, now: number): string {
  let s = Math.max(0, Math.floor((endsAt - now) / 1000))
  const d = Math.floor(s / 86400)
  s -= d * 86400
  const h = Math.floor(s / 3600)
  s -= h * 3600
  const m = Math.floor(s / 60)
  s -= m * 60
  const p = (n: number) => n.toString().padStart(2, '0')
  return `${p(d)}:${p(h)}:${p(m)}:${p(s)}`
}

/** nano -> the plaque figure "1 000 058.80" (grouped, always two decimals). */
export function formatPoolDec(nano: number): string {
  const [int, frac] = (nano / NANO).toFixed(2).split('.')
  return int!.replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + '.' + frac
}
