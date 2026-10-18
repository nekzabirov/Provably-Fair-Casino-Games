// PvP is TON only. Single rule, but kept in the same shape as the
// other clients so helpers (toMinor/fromMinor/formatAmount) read identically.

export interface CurrencyRule {
  /** Minimum stake. */
  min: number
  /** Increment/decrement step for the bet stepper. */
  step: number
  /** Decimal places used to display and round the amount. */
  decimals: number
  /** Integer system units (nano, 10^9) per 1 display unit sent to the engine. */
  scale: number
  /** Fixed decimals for full payout amounts. */
  payoutDecimals: number
  /** User-facing currency name. */
  label: string
  /** Quick-set bet amounts shown as preset chips. */
  presets: number[]
}

export const TON: CurrencyRule = {
  min: 0.1,
  step: 0.1,
  decimals: 1,
  scale: 1_000_000_000,
  payoutDecimals: 4,
  label: 'TON',
  presets: [1, 5, 10, 50],
}

/** Only TON is supported. */
export function currencyRule(code: string): CurrencyRule | null {
  return code === 'TON' ? TON : null
}

export function roundToStep(value: number, rule: CurrencyRule): number {
  return Number(value.toFixed(rule.decimals))
}

/** Formats an amount, trimming a trailing ".0". */
export function formatAmount(value: number, rule: CurrencyRule): string {
  return value.toFixed(rule.decimals).replace(/\.0+$/, '')
}

/** Major units -> integer minor units (what the engine stores). */
export function toMinor(value: number, rule: CurrencyRule): number {
  return Math.round(value * rule.scale)
}

/** Integer minor units from the engine -> major units for display. */
export function fromMinor(minor: number, rule: CurrencyRule): number {
  return minor / rule.scale
}

/** Full payout amount with fixed precision (not trimmed). */
export function formatPayout(minor: number, rule: CurrencyRule): string {
  return fromMinor(minor, rule).toFixed(rule.payoutDecimals)
}
