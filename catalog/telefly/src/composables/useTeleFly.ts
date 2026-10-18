import { markRaw, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { config } from '../config'
import { currencyRule, formatAmount, fromMinor } from '../currency'
import { CrashClient } from '../net/CrashClient'
import type { BetView, Phase, PlayerInfo } from '../net/protocol'
import { playExplosion, startEngine, stopEngine, updateEngine } from '../sound'

export interface FeedItem {
  id: number
  kind: 'bet' | 'cashout'
  username: string
  amount?: number
  multiplier?: number
  currency: string
}

/** A cashed-out player to render as a mini plane flying across the sky. */
export interface Flyby {
  name: string
  detail: string
}

/** The seeds of the most recent crashed round, for provably-fair verification. */
export interface RevealedRound {
  serverSeed: string
  /** The commitment broadcast before the round — SHA-256(serverSeed) must match it. */
  serverSeedHash: string
  /** The public TON block-hash seed used for the round. */
  clientSeed: string
  roundNonce: number
  crashMultiplier: number
}

/**
 * Live, non-reactive holder polled by the Pixi canvas every frame. Tick frames
 * (~10/s) write here and never touch a reactive ref, so the 60fps canvas can read
 * the multiplier without ever triggering a Vue re-render. [shownMultiplier] is
 * owned by the canvas (the eased value it draws); the composable only seeds it on
 * reconnect.
 */
export interface LiveState {
  phase: Phase
  targetMultiplier: number
  shownMultiplier: number
  crashMultiplier: number | null
  /** Epoch millis the current phase ends — drives the in-canvas betting countdown. */
  phaseEndsAt: number
  /** Queue of cashed-out players for the canvas to spawn as mini planes (drained each frame). */
  flybys: Flyby[]
}

/**
 * Reactive bridge around [CrashClient]. High-frequency ticks bypass Vue (the
 * `live` holder); only phase / bet / feed / history changes — a handful per round —
 * flow through refs. The currency is fixed to `config.currency` (TON) for the
 * connection lifetime.
 */
export function useTeleFly() {
  const connected = ref(false)
  const phase = ref<Phase>('CRASHED')
  const player = shallowRef<PlayerInfo | null>(null)
  const bet = shallowRef<BetView | null>(null)
  const history = shallowRef<number[]>([])
  const feed = shallowRef<FeedItem[]>([])
  const phaseEndsAt = ref(0)
  const serverSeedHash = ref('')
  const crashMultiplier = ref<number | null>(null)
  const lastRound = shallowRef<RevealedRound | null>(null)
  const players = ref(0)
  const error = ref<string | null>(null)
  const fatalError = ref<string | null>(null)
  const currency = ref(config.currency)

  const live: LiveState = markRaw({
    phase: 'CRASHED',
    targetMultiplier: 1,
    shownMultiplier: 1,
    crashMultiplier: null,
    phaseEndsAt: 0,
    flybys: [],
  })

  let authed = false
  let feedId = 0
  let client: CrashClient

  function applyPhase(p: Phase): void {
    const prev = phase.value
    if (p === prev) return
    phase.value = p
    live.phase = p
    if (p === 'FLYING') {
      crashMultiplier.value = null
      live.crashMultiplier = null
      startEngine()
    } else if (p === 'BETTING') {
      bet.value = null
      crashMultiplier.value = null
      live.crashMultiplier = null
      live.targetMultiplier = 1
      stopEngine()
    }
  }

  function pushFeed(item: Omit<FeedItem, 'id'>): void {
    const next = [{ id: feedId++, ...item }, ...feed.value]
    if (next.length > 30) next.length = 30
    feed.value = next
  }

  function build(code: string): void {
    client = new CrashClient(config.wsUrl, config.sessionToken, code, config.operator)

    client.onStatus((c) => (connected.value = c))

    client.onState((s) => {
      authed = true
      error.value = null
      player.value = s.player
      bet.value = s.bet ?? null
      history.value = s.history
      phaseEndsAt.value = s.phaseEndsAt
      live.phaseEndsAt = s.phaseEndsAt
      serverSeedHash.value = s.serverSeedHash
      players.value = s.players
      // Reconnect mid-flight: seed the canvas so it catches up to the live curve.
      if (s.phase === 'FLYING') {
        live.targetMultiplier = s.multiplier
        live.shownMultiplier = Math.max(live.shownMultiplier, s.multiplier)
      }
      applyPhase(s.phase)
    })

    client.onPhase((p) => {
      history.value = p.history
      phaseEndsAt.value = p.phaseEndsAt
      live.phaseEndsAt = p.phaseEndsAt
      serverSeedHash.value = p.serverSeedHash
      players.value = p.players
      applyPhase(p.phase)
    })

    client.onTick((t) => {
      // Hot path: no reactive writes.
      live.targetMultiplier = t.multiplier
      updateEngine(t.multiplier)
    })

    client.onCrash((c) => {
      crashMultiplier.value = c.crashMultiplier
      lastRound.value = {
        serverSeed: c.serverSeed,
        // serverSeedHash still holds this round's commitment until the next phase frame.
        serverSeedHash: serverSeedHash.value,
        clientSeed: c.clientSeed,
        roundNonce: c.roundNonce,
        crashMultiplier: c.crashMultiplier,
      }
      live.crashMultiplier = c.crashMultiplier
      live.targetMultiplier = c.crashMultiplier
      live.phase = 'CRASHED'
      phase.value = 'CRASHED'
      history.value = [...history.value, c.crashMultiplier].slice(-25)
      stopEngine()
      playExplosion()
    })

    client.onBets((b) => {
      if (b.type === 'betPlaced') {
        players.value = b.players
        pushFeed({ kind: 'bet', username: b.username, amount: b.amount, currency: b.currency })
      } else {
        pushFeed({ kind: 'cashout', username: b.username, multiplier: b.multiplier, currency: b.currency })
        // Queue a mini-plane flyby with the player's name + win (canvas drains it).
        const rule = currencyRule(b.currency)
        const detail = rule
          ? `+${formatAmount(fromMinor(b.payout, rule), rule)} ${rule.label}`
          : `×${b.multiplier.toFixed(2)}`
        live.flybys.push({ name: b.username, detail })
        if (live.flybys.length > 12) live.flybys.shift()
      }
    })

    client.onError((e) => {
      error.value = e
      if (!authed) fatalError.value = e
    })

    if (import.meta.env.DEV) {
      ;(window as unknown as { telefly: CrashClient }).telefly = client
      ;(window as unknown as { __teleflyLive: LiveState }).__teleflyLive = live
    }
    client.connect()
  }

  onMounted(() => build(currency.value))
  onBeforeUnmount(() => {
    client.disconnect()
    stopEngine()
  })

  return {
    connected,
    phase,
    player,
    bet,
    history,
    feed,
    phaseEndsAt,
    serverSeedHash,
    crashMultiplier,
    lastRound,
    players,
    error,
    fatalError,
    currency,
    live,
    placeBet: (amount: number) => client.bet(amount),
    cashout: () => client.cashout(),
  }
}
