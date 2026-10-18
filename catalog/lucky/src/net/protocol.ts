// Wire protocol for /ws/shell. Mirrors the engine's ShellState / command frames.

export type GameState = 'WAIT' | 'ACTIVE' | 'WIN' | 'LOSE'

export interface PlayerInfo {
  username: string
  balance: number
  profilePic?: string | null
}

/** Provably-fair data for the in-game verifier (mirrors ShellState.Fairness). */
export interface Fairness {
  serverSeedHash: string
  clientSeed: string
  tonSeed?: string | null
  nonce?: number | null
  /** Revealed only once the round resolves, so the cup can be reproduced. */
  serverSeed?: string | null
}

/** The single response shape the engine sends on every command. */
export interface ShellState {
  player: PlayerInfo
  state: GameState
  placeAmount: number
  settleAmount: number
  /** Cup (0..2) holding the ball; -1 until the round resolves (WIN/LOSE). */
  winningCup: number
  fairness: Fairness
}

export interface ErrorFrame {
  type: 'error'
  error: string
}

export type ServerFrame = ShellState | ErrorFrame

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
}

export interface PickCommand {
  type: 'pick'
  cup: number
}

export type ClientCommand = AuthCommand | StartCommand | PickCommand

export function isErrorFrame(frame: ServerFrame): frame is ErrorFrame {
  return (frame as ErrorFrame).type === 'error'
}
