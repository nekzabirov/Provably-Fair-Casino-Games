// Wire protocol for /ws/mines. Mirrors the engine's MinesState / command frames.

export type GameState = 'ACTIVE' | 'WAIT' | 'FAIL'

export interface PlayerInfo {
  username: string
  balance: number
  profilePic?: string | null
}

export interface Cell {
  index: number
  type: 'DIAMOND' | 'MINE'
  /** Cumulative multiplier at the moment this diamond was revealed. */
  multiplier: number
}

/** Provably-fair data for the in-game verifier (mirrors MinesState.Fairness). */
export interface Fairness {
  serverSeedHash: string
  clientSeed: string
  tonSeed?: string | null
  nonce?: number | null
  /** Revealed only once the round closes, so the layout can be reproduced. */
  serverSeed?: string | null
}

/** The single response shape the engine sends on every command. */
export interface MinesState {
  player: PlayerInfo
  state: GameState
  placeAmount: number
  settleAmount: number
  mineCount: number
  cells: Cell[]
  fairness: Fairness
}

export interface ErrorFrame {
  type: 'error'
  error: string
}

export type ServerFrame = MinesState | ErrorFrame

// ── Client → server commands ────────────────────────────────────────────────

export interface AuthCommand {
  type: 'auth'
  token: string
  currency: string
  operator: string
  /** The player's provably-fair client seed; the round mixes it into the outcome. */
  clientSeed: string
}

export interface StartCommand {
  type: 'start'
  amount: number
  mineCount: number
}

export interface OpenCommand {
  type: 'open'
  index: number
}

export interface CashoutCommand {
  type: 'cashout'
}

export type ClientCommand = AuthCommand | StartCommand | OpenCommand | CashoutCommand

export function isErrorFrame(frame: ServerFrame): frame is ErrorFrame {
  return (frame as ErrorFrame).type === 'error'
}
