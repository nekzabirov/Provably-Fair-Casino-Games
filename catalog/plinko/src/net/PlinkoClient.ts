import {
  isErrorFrame,
  type ClientCommand,
  type PlinkoState,
  type ServerFrame,
} from './protocol'

type StateHandler = (state: PlinkoState) => void
type ErrorHandler = (error: string) => void
type StatusHandler = (connected: boolean) => void
type Unsubscribe = () => void

/**
 * WebSocket client for the Plinko game. One socket per player; the currency is
 * sent once on `auth` (it lives on the connection, not on each command). Drops are
 * fire-and-forget: each `drop` answers with a RESULT state carrying the ball path.
 */
export class PlinkoClient {
  private ws: WebSocket | null = null

  private closedByUser = false

  private reconnectAttempt = 0

  private reconnectTimer: ReturnType<typeof setTimeout> | null = null

  /** Most recent state / error, handy for imperative callers and debugging. */
  lastState: PlinkoState | null = null
  lastError: string | null = null

  private readonly stateHandlers = new Set<StateHandler>()
  private readonly errorHandlers = new Set<ErrorHandler>()
  private readonly statusHandlers = new Set<StatusHandler>()

  constructor(
    private readonly url: string,
    private readonly token: string,
    private readonly currency: string,
    private readonly operator: string,
    private readonly clientSeed: string,
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
      this.send({
        type: 'auth',
        token: this.token,
        currency: this.currency,
        operator: this.operator,
        clientSeed: this.clientSeed,
      })
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

  drop(amount: number, rows: number, risk: string): void {
    this.send({ type: 'drop', amount, rows, risk })
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

  /** Exponential backoff up to 10s; auth re-establishes the session on reopen. */
  private scheduleReconnect(): void {
    if (this.closedByUser || this.reconnectTimer != null) return
    const delay = Math.min(500 * 2 ** this.reconnectAttempt, 10_000)
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  onState(handler: StateHandler): Unsubscribe {
    this.stateHandlers.add(handler)
    return () => this.stateHandlers.delete(handler)
  }

  onError(handler: ErrorHandler): Unsubscribe {
    this.errorHandlers.add(handler)
    return () => this.errorHandlers.delete(handler)
  }

  onStatus(handler: StatusHandler): Unsubscribe {
    this.statusHandlers.add(handler)
    return () => this.statusHandlers.delete(handler)
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
    if (isErrorFrame(frame)) {
      this.emitError(frame.error)
    } else {
      this.lastState = frame
      this.lastError = null
      this.stateHandlers.forEach((h) => h(frame))
    }
  }

  private emitError(message: string): void {
    this.lastError = message
    this.errorHandlers.forEach((h) => h(message))
  }

  private emitStatus(connected: boolean): void {
    this.statusHandlers.forEach((h) => h(connected))
  }
}
