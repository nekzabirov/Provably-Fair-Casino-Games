// Wire protocol for /ws/pvp. Mirrors the engine's PvpState / PvpPhaseFrame /
// PvpSpinFrame. Several frame shapes, switched on `type`.

export type Phase = 'WAITING' | 'COUNTDOWN' | 'SPINNING' | 'PAYOUT'
export type BetStatus = 'PLACED' | 'WON' | 'LOST'

export interface PlayerInfo {
  username: string
  balance: number
  profilePic?: string | null
}

/** One participant's arc. `order` is the within-round index (stable for the round). */
export interface Seat {
  order: number
  username: string
  stake: number
  profilePic?: string | null
}

export interface BetView {
  order: number
  stake: number
  status: BetStatus
}

export interface HistoryItem {
  winningNumber: number
  winnerUsername: string
  pot: number
}

/** Personal reply to auth / bet / cancel. */
export interface StateFrame {
  type: 'state'
  player: PlayerInfo
  phase: Phase
  roundNonce: number
  serverSeedHash: string
  phaseEndsAt: number
  pot: number
  players: number
  roster: Seat[]
  bet?: BetView | null
  history: HistoryItem[]
}

/** Global phase / roster / pot snapshot - drives the wheel arcs + countdown for everyone. */
export interface PhaseFrame {
  type: 'phase'
  phase: Phase
  roundNonce: number
  serverSeedHash: string
  phaseEndsAt: number
  pot: number
  players: number
  roster: Seat[]
  history: HistoryItem[]
}

/** Round locked & decided - reveals seeds; the client spins to `winningNumber`. */
export interface SpinFrame {
  type: 'spin'
  winningNumber: number
  winnerUsername: string
  winnerProfilePic?: string | null
  pot: number
  payout: number
  serverSeed: string
  clientSeed: string
  roundNonce: number
  roster: Seat[]
}

export interface ErrorFrame {
  type: 'error'
  error: string
}

export type ServerFrame = StateFrame | PhaseFrame | SpinFrame | ErrorFrame

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

export interface CancelCommand {
  type: 'cancel'
}

export type ClientCommand = AuthCommand | BetCommand | CancelCommand
