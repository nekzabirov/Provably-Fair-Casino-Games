import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { config } from '../config'
import { PvpClient } from '../net/PvpClient'
import type { BetView, HistoryItem, Phase, PlayerInfo, Seat, SpinFrame } from '../net/protocol'

/**
 * Reactive bridge around [PvpClient]. PvP has no high-frequency tick stream (the
 * only animation, the wheel spin, is self-driven by the canvas once it's handed a
 * target), so plain reactive refs are enough - phase / roster / pot change only a
 * handful of times per round.
 *
 * `onSpin` lets the screen react to the lock the instant it arrives (spin the
 * wheel, then reveal the winner); `lastSpin` is kept for the fairness modal.
 */
export function usePvp() {
  const connected = ref(false)
  const error = ref<string | null>(null)
  const fatalError = ref<string | null>(null)

  const player = shallowRef<PlayerInfo | null>(null)
  const phase = ref<Phase>('WAITING')
  const roundNonce = ref(0)
  const serverSeedHash = ref('')
  const phaseEndsAt = ref(0)
  const pot = ref(0)
  const players = ref(0)
  const roster = shallowRef<Seat[]>([])
  const bet = shallowRef<BetView | null>(null)
  const history = shallowRef<HistoryItem[]>([])
  const lastSpin = shallowRef<SpinFrame | null>(null)

  const spinHandlers = new Set<(s: SpinFrame) => void>()

  // In-flight flag for enter-battle / cancel. The engine confirms an action by
  // pushing a fresh state/phase (or an error), which under load can lag a beat -
  // without this the button just sits there looking frozen. Set on send, cleared
  // by the next server frame; a safety timeout guarantees it never sticks.
  const busy = ref(false)
  let busyTimer = 0
  function setBusy() {
    busy.value = true
    window.clearTimeout(busyTimer)
    busyTimer = window.setTimeout(() => (busy.value = false), 8000)
  }
  function clearBusy() {
    busy.value = false
    window.clearTimeout(busyTimer)
  }

  let authed = false
  const client = new PvpClient(config.wsUrl, config.sessionToken, config.currency, config.operator)

  client.onStatus((c) => {
    connected.value = c
    if (!c) clearBusy() // a drop resolves any in-flight action visually
  })

  client.onState((s) => {
    authed = true
    error.value = null
    clearBusy()
    player.value = s.player
    phase.value = s.phase
    roundNonce.value = s.roundNonce
    serverSeedHash.value = s.serverSeedHash
    phaseEndsAt.value = s.phaseEndsAt
    pot.value = s.pot
    players.value = s.players
    roster.value = s.roster
    bet.value = s.bet ?? null
    history.value = s.history
  })

  client.onPhase((p) => {
    clearBusy()
    // A fresh round (nonce advanced) clears our previous stake.
    if (p.roundNonce !== roundNonce.value) bet.value = null
    phase.value = p.phase
    roundNonce.value = p.roundNonce
    serverSeedHash.value = p.serverSeedHash
    phaseEndsAt.value = p.phaseEndsAt
    pot.value = p.pot
    players.value = p.players
    roster.value = p.roster
    history.value = p.history
  })

  client.onSpin((s) => {
    clearBusy()
    lastSpin.value = s
    phase.value = 'SPINNING'
    pot.value = s.pot
    roster.value = s.roster
    spinHandlers.forEach((h) => h(s))
  })

  client.onError((e) => {
    clearBusy()
    error.value = e
    if (!authed) fatalError.value = e
  })

  if (import.meta.env.DEV) {
    ;(window as unknown as { pvp: PvpClient }).pvp = client
  }

  function onSpin(handler: (s: SpinFrame) => void): () => void {
    spinHandlers.add(handler)
    return () => spinHandlers.delete(handler)
  }

  onMounted(() => client.connect())
  onBeforeUnmount(() => {
    window.clearTimeout(busyTimer)
    client.disconnect()
  })

  return {
    connected,
    error,
    fatalError,
    busy,
    player,
    phase,
    roundNonce,
    serverSeedHash,
    phaseEndsAt,
    pot,
    players,
    roster,
    bet,
    history,
    lastSpin,
    onSpin,
    placeBet: (amount: number) => {
      setBusy()
      client.bet(amount)
    },
    cancel: () => {
      setBusy()
      client.cancel()
    },
  }
}
