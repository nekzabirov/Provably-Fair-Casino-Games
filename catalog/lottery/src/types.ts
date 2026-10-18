// Wire shapes from the engine's lottery REST API (POST /api/v1/lottery/state | /buy).
// Money is integer minor units (TON nano). Numbers are 1..49; a system ticket holds
// minPicks..maxPicks of them and costs ticketPrice × (n − minPicks + 1)².

export interface LotteryTicketDto {
  id: number
  numbers: number[]
  matched?: boolean | null
  payout?: number | null
}

export interface LotteryState {
  type: 'lottery'
  prizePool: number
  currency: string
  /** Price of a minimum (6-number) ticket; an n-number ticket costs (n − minPicks + 1)² times this. */
  ticketPrice: number
  tickets: LotteryTicketDto[]
  ticketCount: number
  maxTickets: number
  /** Minimum numbers on a ticket (= how many the draw reveals). */
  minPicks: number
  /** Maximum numbers on a system ticket. */
  maxPicks: number
  /** Probability of winning the jackpot with the held ticket, in [0, 1]. */
  winChance: number
  startsAt: number
  endsAt: number
  winningNumbers: number[]
  bettingOpen: boolean
  finished: boolean
  drawNonce: number
  serverSeedHash: string
  serverSeed?: string | null
  tonSeed?: string | null
}

export interface ApiError {
  error: string
}
