import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { config } from '../config'
import { loadClientSeed, saveClientSeed } from '../fairness'
import { PlinkoClient } from '../net/PlinkoClient'
import type { PlinkoState } from '../net/protocol'

/**
 * Reactive wrapper around [PlinkoClient]. Connects on mount and authenticates with
 * the sessionToken + currency + operator + the player's provably-fair client seed.
 *
 * Drops are rapid-fire: the screen may send several before the first resolves, so
 * besides the latest `state` snapshot the composable exposes `onResult` — every
 * RESULT frame in arrival order — which the board uses to spawn one animated ball
 * per drop.
 *
 * - `fatalError` is set when something fails before auth ever succeeds (bad/expired
 *   token, no token, or no connection) — the caller should render an error screen.
 * - `error` carries transient in-game errors (after a successful auth).
 */
export function usePlinko() {
  const connected = ref(false)
  const state = shallowRef<PlinkoState | null>(null)
  const error = ref<string | null>(null)
  const fatalError = ref<string | null>(null)
  const currency = ref(config.currency)
  const clientSeed = ref(loadClientSeed())
  /** Drops sent but not yet answered (used to cap the burst). */
  const pending = ref(0)

  const resultHandlers = new Set<(s: PlinkoState) => void>()

  let authed = false
  let client: PlinkoClient

  function build(code: string): void {
    client = new PlinkoClient(config.wsUrl, config.sessionToken, code, config.operator, clientSeed.value)
    client.onStatus((c) => (connected.value = c))
    client.onState((s) => {
      authed = true
      state.value = s
      error.value = null
      if (s.state === 'RESULT') {
        pending.value = Math.max(0, pending.value - 1)
        resultHandlers.forEach((h) => h(s))
      }
    })
    client.onError((e) => {
      error.value = e
      pending.value = Math.max(0, pending.value - 1)
      // An error before the first successful state means auth never went through.
      if (!authed) fatalError.value = e
    })
    if (import.meta.env.DEV) {
      ;(window as unknown as { plinko: PlinkoClient }).plinko = client
    }
    client.connect()
  }

  function reconnect(code: string): void {
    client.disconnect()
    authed = false
    connected.value = false
    state.value = null
    error.value = null
    fatalError.value = null
    pending.value = 0
    build(code)
  }

  /**
   * Change the provably-fair client seed. Persists it and reconnects so the next
   * drop uses it. Instant game — no active round can hold it hostage.
   */
  function setClientSeed(seed: string): void {
    if (!seed || seed === clientSeed.value) return
    clientSeed.value = seed
    saveClientSeed(seed)
    reconnect(currency.value)
  }

  /** Every RESULT frame, in arrival order. Returns an unsubscribe. */
  function onResult(handler: (s: PlinkoState) => void): () => void {
    resultHandlers.add(handler)
    return () => resultHandlers.delete(handler)
  }

  function drop(amount: number, rows: number, risk: string): void {
    pending.value++
    client.drop(amount, rows, risk)
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
    pending,
    setClientSeed,
    onResult,
    drop,
  }
}
