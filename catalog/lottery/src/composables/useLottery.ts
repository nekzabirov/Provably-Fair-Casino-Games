import { computed, onUnmounted, ref } from 'vue'
import { buyTicket, fetchState, LotteryRequestError } from '../net/lottery'
import { requestDeposit } from '../config'
import type { LotteryState } from '../types'

const POLL_MS = 4000

/**
 * Owns the lottery state: an initial load, a light poll loop (pool growth + the
 * one-per-interval number reveal during the draw), and the buy action. The draw is
 * a 2-hour round and low-frequency, so polling is plenty — no socket.
 */
export function useLottery() {
  const state = ref<LotteryState | null>(null)
  const loading = ref(true)
  const fatal = ref<string | null>(null)
  const error = ref<string | null>(null)
  const buying = ref(false)

  let timer: ReturnType<typeof setInterval> | undefined
  let errorTimer: ReturnType<typeof setTimeout> | undefined

  async function load(initial = false): Promise<void> {
    try {
      state.value = await fetchState()
    } catch (e) {
      const msg = e instanceof LotteryRequestError ? e.message : 'Something went wrong'
      // Only fail hard on the FIRST load — a later poll hiccup keeps the last good state.
      if (initial && !state.value) fatal.value = msg
    } finally {
      // Always leave the loading state after the first attempt, success or failure,
      // so a failed initial fetch shows the error screen instead of an endless spinner.
      if (initial) loading.value = false
    }
  }

  function flashError(msg: string): void {
    error.value = msg
    if (errorTimer) clearTimeout(errorTimer)
    errorTimer = setTimeout(() => (error.value = null), 4000)
  }

  async function buy(numbers: number[]): Promise<boolean> {
    if (buying.value) return false
    buying.value = true
    error.value = null
    try {
      state.value = await buyTicket(numbers)
      return true
    } catch (e) {
      if (e instanceof LotteryRequestError && e.status === 402) requestDeposit()
      flashError(e instanceof LotteryRequestError ? e.message : 'Could not buy the ticket')
      return false
    } finally {
      buying.value = false
    }
  }

  void load(true)
  timer = setInterval(() => void load(), POLL_MS)

  onUnmounted(() => {
    if (timer) clearInterval(timer)
    if (errorTimer) clearTimeout(errorTimer)
  })

  const canBuy = computed(() => {
    const s = state.value
    return !!s && s.bettingOpen && s.ticketCount < s.maxTickets
  })

  return { state, loading, fatal, error, buying, buy, canBuy }
}
