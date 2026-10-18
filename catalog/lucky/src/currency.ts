// Per-currency betting rules. TON is the only supported currency; anything else
// is rejected at startup.

export interface CurrencyRule {
  /** Minimum stake. */
  min: number
  /** Increment/decrement step for the bet stepper. No maximum. */
  step: number
  /** Decimal places used to display and round the amount. */
  decimals: number
  /** Integer minor units per 1 display unit sent to the engine. */
  scale: number
  /** Fixed decimals for the full (un-trimmed) payout shown on the result. */
  payoutDecimals: number
  /** User-facing currency name. */
  label: string
}

export const CURRENCY_RULES: Record<string, CurrencyRule> = {
  // Platform "system unit" = nano (10^9): TON is scaled to nano.
  TON: { min: 0.1, step: 0.1, decimals: 1, scale: 1_000_000_000, payoutDecimals: 4, label: 'TON' },
}

export function currencyRule(code: string): CurrencyRule | null {
  return CURRENCY_RULES[code] ?? null
}

/** Rounds to the currency's precision, killing float drift (e.g. 0.30000000000004). */
export function roundToStep(value: number, rule: CurrencyRule): number {
  return Number(value.toFixed(rule.decimals))
}

/** Formats an amount, trimming a trailing ".0" (e.g. 100.0 -> "100", 0.1 -> "0.1"). */
export function formatAmount(value: number, rule: CurrencyRule): string {
  return value.toFixed(rule.decimals).replace(/\.0+$/, '')
}

/** Major units (what the user sees) -> integer minor units (what the engine stores). */
export function toMinor(value: number, rule: CurrencyRule): number {
  return Math.round(value * rule.scale)
}

/** Integer minor units from the engine -> major units for display. */
export function fromMinor(minor: number, rule: CurrencyRule): number {
  return minor / rule.scale
}

/** Full payout amount with the currency's fixed precision (not trimmed). */
export function formatPayout(minor: number, rule: CurrencyRule): string {
  return fromMinor(minor, rule).toFixed(rule.payoutDecimals)
}
