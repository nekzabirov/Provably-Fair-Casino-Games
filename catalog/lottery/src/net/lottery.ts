import { config } from '../config'
import type { ApiError, LotteryState } from '../types'

// REST client for the engine's player-facing lottery API. The session token + operator
// authenticate every call (no secret). A non-2xx body is `{ error }`; we surface it.

export class LotteryRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

async function post<T>(path: string, body: Record<string, unknown>): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${config.apiBase}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    throw new LotteryRequestError('Network error, check your connection', 0)
  }
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as ApiError | null
    throw new LotteryRequestError(err?.error ?? `Request failed (${res.status})`, res.status)
  }
  return (await res.json()) as T
}

const auth = () => ({ token: config.sessionToken, operator: config.operator })

/** Current draw + this player's tickets and win chance. */
export function fetchState(): Promise<LotteryState> {
  return post<LotteryState>('/api/v1/lottery/state', auth())
}

/** Buy one ticket (six distinct numbers, 1..49) into the current draw. */
export function buyTicket(numbers: number[]): Promise<LotteryState> {
  return post<LotteryState>('/api/v1/lottery/buy', { ...auth(), numbers })
}
