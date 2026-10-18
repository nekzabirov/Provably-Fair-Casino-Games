import type {
  BetsFrame,
  ClientCommand,
  CrashFrame,
  PhaseFrame,
  ServerFrame,
  StateFrame,
  TickFrame,
} from './protocol'

type StateHandler = (state: StateFrame) => void
type PhaseHandler = (phase: PhaseFrame) => void
type TickHandler = (tick: TickFrame) => void
type CrashHandler = (crash: CrashFrame) => void
type BetsHandler = (bets: BetsFrame) => void
type ErrorHandler = (error: string) => void
type StatusHandler = (connected: boolean) => void
type Unsubscribe = () => void

/**
 * WebSocket client for TeleFly. One socket per player; the currency is sent once
 * on `auth`. The server is the source of truth: it pushes phase / tick / crash /
 * feed frames to everyone and a personal `state` frame in reply to auth/bet/cashout.
 * The client routes each frame by its `type` discriminator.
 */
export class CrashClient {
  private ws: WebSocket | null = null

  private closedByUser = false

  private reconnectAttempt = 0

  private reconnectTimer: ReturnType<typeof setTimeout> | null = null

  private readonly stateHandlers = new Set<StateHandler>()
  private readonly phaseHandlers = new Set<PhaseHandler>()
  private readonly tickHandlers = new Set<TickHandler>()
  private readonly crashHandlers = new Set<CrashHandler>()
  private readonly betsHandlers = new Set<BetsHandler>()
  private readonly errorHandlers = new Set<ErrorHandler>()
  private readonly statusHandlers = new Set<StatusHandler>()

  constructor(
    private readonly url: string,
    private readonly token: string,
    private readonly currency: string,
    private readonly operator: string,
  ) {}

  /** Opens the socket and authenticates as soon as it is ready. */
  connect(): void {
    if (this.ws) return
    this.closedByUser = false

    const ws = new WebSocket(this.url)
    this.ws = ws

    ws.onopen = () => {
      this.reconnectAttempt = 0
      this.emitStatus(true)
      this.send({ type: 'auth', token: this.token, currency: this.currency, operator: this.operator })
    }
    ws.onmessage = (event) => this.handleFrame(event.data)
    ws.onerror = () => this.emitError('connection error')
    ws.onclose = (event) => {
      this.ws = null
      this.emitStatus(false)
      // A normal close (code 1000) is deliberate — manual disconnect or the server
      // replacing this socket with a newer one for the same player; reconnecting
      // would fight that newer connection. Anything else (proxy idle kill, network
      // blip, server restart) is recovered automatically.
      if (event.code !== 1000) this.scheduleReconnect()
    }
  }

  bet(amount: number): void {
    this.send({ type: 'bet', amount })
  }

  cashout(): void {
    this.send({ type: 'cashout' })
  }

  disconnect(): void {
    this.closedByUser = true
    if (this.reconnectTimer != null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
  }

  /** Exponential backoff up to 10s; auth re-joins the shared round on reopen. */
  private scheduleReconnect(): void {
    if (this.closedByUser || this.reconnectTimer != null) return
    const delay = Math.min(500 * 2 ** this.reconnectAttempt, 10_000)
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  onState(h: StateHandler): Unsubscribe {
    this.stateHandlers.add(h)
    return () => this.stateHandlers.delete(h)
  }

  onPhase(h: PhaseHandler): Unsubscribe {
    this.phaseHandlers.add(h)
    return () => this.phaseHandlers.delete(h)
  }

  onTick(h: TickHandler): Unsubscribe {
    this.tickHandlers.add(h)
    return () => this.tickHandlers.delete(h)
  }

  onCrash(h: CrashHandler): Unsubscribe {
    this.crashHandlers.add(h)
    return () => this.crashHandlers.delete(h)
  }

  onBets(h: BetsHandler): Unsubscribe {
    this.betsHandlers.add(h)
    return () => this.betsHandlers.delete(h)
  }

  onError(h: ErrorHandler): Unsubscribe {
    this.errorHandlers.add(h)
    return () => this.errorHandlers.delete(h)
  }

  onStatus(h: StatusHandler): Unsubscribe {
    this.statusHandlers.add(h)
    return () => this.statusHandlers.delete(h)
  }

  private send(command: ClientCommand): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(command))
    }
  }

  private handleFrame(raw: unknown): void {
    if (typeof raw !== 'string') return
    let frame: ServerFrame
    try {
      frame = JSON.parse(raw) as ServerFrame
    } catch {
      this.emitError('malformed server frame')
      return
    }
    switch (frame.type) {
      case 'state':
        this.stateHandlers.forEach((h) => h(frame))
        break
      case 'phase':
        this.phaseHandlers.forEach((h) => h(frame))
        break
      case 'tick':
        this.tickHandlers.forEach((h) => h(frame))
        break
      case 'crash':
        this.crashHandlers.forEach((h) => h(frame))
        break
      case 'betPlaced':
      case 'betCashout':
        this.betsHandlers.forEach((h) => h(frame))
        break
      case 'error':
        this.emitError(frame.error)
        break
    }
  }

  private emitError(message: string): void {
    this.errorHandlers.forEach((h) => h(message))
  }

  private emitStatus(connected: boolean): void {
    this.statusHandlers.forEach((h) => h(connected))
  }
}
