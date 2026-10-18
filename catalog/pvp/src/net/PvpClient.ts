import type {
  ClientCommand,
  ErrorFrame,
  PhaseFrame,
  ServerFrame,
  SpinFrame,
  StateFrame,
} from './protocol'

type StateHandler = (state: StateFrame) => void
type PhaseHandler = (phase: PhaseFrame) => void
type SpinHandler = (spin: SpinFrame) => void
type ErrorHandler = (error: string) => void
type StatusHandler = (connected: boolean) => void
type Unsubscribe = () => void

/**
 * WebSocket client for PvP. One socket per player; currency sent once on `auth`.
 * The server is the source of truth: it pushes phase / spin frames to everyone and
 * a personal `state` frame in reply to auth/bet/cancel. Auto-reconnects with
 * exponential backoff (auth restores the player's stake on reopen).
 */
export class PvpClient {
  private ws: WebSocket | null = null

  private closedByUser = false
  private reconnectAttempt = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null

  private readonly stateHandlers = new Set<StateHandler>()
  private readonly phaseHandlers = new Set<PhaseHandler>()
  private readonly spinHandlers = new Set<SpinHandler>()
  private readonly errorHandlers = new Set<ErrorHandler>()
  private readonly statusHandlers = new Set<StatusHandler>()

  constructor(
    private readonly url: string,
    private readonly token: string,
    private readonly currency: string,
    private readonly operator: string,
  ) {}

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
      // Code 1000 = deliberate (manual disconnect or server replacing this socket);
      // anything else (proxy idle kill, blip, restart) is recovered automatically.
      if (event.code !== 1000) this.scheduleReconnect()
    }
  }

  bet(amount: number): void {
    this.send({ type: 'bet', amount })
  }

  cancel(): void {
    this.send({ type: 'cancel' })
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

  onSpin(h: SpinHandler): Unsubscribe {
    this.spinHandlers.add(h)
    return () => this.spinHandlers.delete(h)
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
      case 'spin':
        this.spinHandlers.forEach((h) => h(frame))
        break
      case 'error':
        this.emitError((frame as ErrorFrame).error)
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
