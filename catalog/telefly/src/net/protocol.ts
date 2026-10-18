// Wire protocol for /ws/telefly. Mirrors the engine's TeleFlyState / frame shapes.
// Unlike Mines (one shape), the client receives several frame types and switches
// on `type`.

export type Phase = 'BETTING' | 'FLYING' | 'CRASHED'
export type BetStatus = 'PLACED' | 'CASHED' | 'LOST'

export interface PlayerInfo {
  username: string
  balance: number
  profilePic?: string | null
}

export interface BetView {
  placeAmount: number
  cashoutMultiplier?: number | null
  settleAmount: number
  status: BetStatus
}

/** Personal reply to auth / bet / cashout. */
export interface StateFrame {
  type: 'state'
  player: PlayerInfo
  phase: Phase
  serverSeedHash: string
  multiplier: number
  /** Epoch millis the current phase ends — seeds the betting countdown. */
  phaseEndsAt: number
  /** Number of bets in the current round (live players counter). */
  players: number
  bet?: BetView | null
  history: number[]
}

/** Global phase transition. */
export interface PhaseFrame {
  type: 'phase'
  phase: Phase
  roundNonce: number
  serverSeedHash: string
  phaseEndsAt: number
  players: number
  history: number[]
}

/** Live multiplier during FLYING (~10/s). */
export interface TickFrame {
  type: 'tick'
  multiplier: number
}

/** Round crashed — reveals the seed for provably-fair verification. */
export interface CrashFrame {
  type: 'crash'
  crashMultiplier: number
  serverSeed: string
  clientSeed: string
  roundNonce: number
}

/** Live feed: a player placed a bet. */
export interface BetPlacedFrame {
  type: 'betPlaced'
  username: string
  amount: number
  currency: string
  players: number
  profilePic?: string | null
}

/** Live feed: a player cashed out. */
export interface BetCashoutFrame {
  type: 'betCashout'
  username: string
  multiplier: number
  payout: number
  currency: string
  profilePic?: string | null
}

export interface ErrorFrame {
  type: 'error'
  error: string
}

export type BetsFrame = BetPlacedFrame | BetCashoutFrame

export type ServerFrame =
  | StateFrame
  | PhaseFrame
  | TickFrame
  | CrashFrame
  | BetPlacedFrame
  | BetCashoutFrame
  | ErrorFrame

// ── Client → server commands ────────────────────────────────────────────────

export interface AuthCommand {
  type: 'auth'
  token: string
  currency: string
  operator: string
}

export interface BetCommand {
  type: 'bet'
  amount: number
}

export interface CashoutCommand {
  type: 'cashout'
}

export type ClientCommand = AuthCommand | BetCommand | CashoutCommand
