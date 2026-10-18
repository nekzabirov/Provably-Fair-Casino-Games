// Wire protocol for /ws/plinko. Mirrors the engine's PlinkoState / command frames.

export type GameState = 'WAIT' | 'RESULT'

export interface PlayerInfo {
  username: string
  balance: number
  profilePic?: string | null
}

/** Provably-fair data for the in-game verifier (mirrors PlinkoState.Fairness). */
export interface Fairness {
  serverSeedHash: string
  clientSeed: string
  tonSeed?: string | null
  nonce?: number | null
  /** Plinko resolves instantly, so the seed is revealed with the result. */
  serverSeed?: string | null
}

/** The single response shape the engine sends on every command. */
export interface PlinkoState {
  player: PlayerInfo
  state: GameState
  rows: number
  risk: string
  placeAmount: number
  settleAmount: number
  /** Landing slot (0..rows); -1 while WAIT. */
  slot: number
  multiplier: number
  /** Bounce per row: 0 = left, 1 = right. Empty while WAIT. */
  path: number[]
  fairness: Fairness
}

export interface ErrorFrame {
  type: 'error'
  error: string
}

export type ServerFrame = PlinkoState | ErrorFrame

// ── Client → server commands ────────────────────────────────────────────────

export interface AuthCommand {
  type: 'auth'
  token: string
  currency: string
  operator: string
  /** The player's provably-fair client seed; every drop mixes it into the outcome. */
  clientSeed: string
}

export interface DropCommand {
  type: 'drop'
  amount: number
  rows: number
  risk: string
}

export type ClientCommand = AuthCommand | DropCommand

export function isErrorFrame(frame: ServerFrame): frame is ErrorFrame {
  return (frame as ErrorFrame).type === 'error'
}
