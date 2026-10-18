import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { config } from '../config'
import { loadClientSeed, saveClientSeed } from '../fairness'
import { ShellClient } from '../net/ShellClient'
import type { ShellState } from '../net/protocol'

/**
 * Reactive wrapper around [ShellClient]. Connects on mount and authenticates with
 * the sessionToken + currency + operator + the player's provably-fair client seed.
 *
 * - `fatalError` is set when something fails before auth ever succeeds (bad/expired
 *   token, no token, or no connection) — the caller should render an error screen.
 * - `error` carries transient in-game errors (after a successful auth).
 */
export function useShell() {
  const connected = ref(false)
  const state = shallowRef<ShellState | null>(null)
  const error = ref<string | null>(null)
  const fatalError = ref<string | null>(null)
  const clientSeed = ref(loadClientSeed())

  let authed = false
  let client: ShellClient

  function build(): void {
    client = new ShellClient(config.wsUrl, config.sessionToken, config.currency, config.operator, clientSeed.value)
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
    build()
  }

  onMounted(() => build())
  onBeforeUnmount(() => client.disconnect())

  return {
    connected,
    state,
    error,
    fatalError,
    clientSeed,
    setClientSeed,
    start: (amount: number) => client.start(amount),
    pick: (cup: number) => client.pick(cup),
  }
}
