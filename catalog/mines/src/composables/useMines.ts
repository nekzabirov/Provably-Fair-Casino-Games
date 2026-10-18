import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { config } from '../config'
import { loadClientSeed, saveClientSeed } from '../fairness'
import { MinesClient } from '../net/MinesClient'
import type { MinesState } from '../net/protocol'

/**
 * Reactive wrapper around [MinesClient]. Connects on mount and authenticates with
 * the sessionToken + currency + operator from the query params. Currency is fixed
 * for the connection lifetime (always TON), sent once on `auth`.
 *
 * - `fatalError` is set when something fails before auth ever succeeds (bad/expired
 *   token, no token, or no connection) — the caller should render an error screen.
 * - `error` carries transient in-game errors (after a successful auth).
 */
export function useMines() {
  const connected = ref(false)
  const state = shallowRef<MinesState | null>(null)
  const error = ref<string | null>(null)
  const fatalError = ref<string | null>(null)
  const currency = ref(config.currency)
  const clientSeed = ref(loadClientSeed())

  let authed = false
  let client: MinesClient

  function build(code: string): void {
    client = new MinesClient(config.wsUrl, config.sessionToken, code, config.operator, clientSeed.value)
    client.onStatus((c) => (connected.value = c))
    client.onState((s) => {
      authed = true
      state.value = s
      error.value = null
    })
    client.onError((e) => {
      error.value = e
      // An error before the first successful state means auth never went through.
      if (!authed) fatalError.value = e
    })
    if (import.meta.env.DEV) {
      ;(window as unknown as { mines: MinesClient }).mines = client
    }
    client.connect()
  }

  /**
   * Change the provably-fair client seed. Persists it and reconnects so the next
   * round uses it (an active round keeps the seed it started with). No-op mid-round.
   */
  function setClientSeed(seed: string): void {
    if (!seed || seed === clientSeed.value) return
    if (state.value?.state === 'ACTIVE') return
    clientSeed.value = seed
    saveClientSeed(seed)
    client.disconnect()
    authed = false
    connected.value = false
    state.value = null
    error.value = null
    fatalError.value = null
    build(currency.value)
  }

  onMounted(() => build(currency.value))
  onBeforeUnmount(() => client.disconnect())

  return {
    connected,
    state,
    error,
    fatalError,
    currency,
    clientSeed,
    setClientSeed,
    start: (amount: number, mineCount: number) => client.start(amount, mineCount),
    open: (index: number) => client.open(index),
    cashout: () => client.cashout(),
  }
}
