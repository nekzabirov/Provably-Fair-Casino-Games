// Runtime config. sessionToken, currency and operator come from the page query params,
// e.g. /?sessionToken=BASE32...&currency=TON&operator=acme — the operator opens the game
// client with these already set. operator is replayed on the WS auth frame so the engine
// resolves the right session.

function queryParam(name: string): string | null {
  return new URLSearchParams(window.location.search).get(name)
}

/** Same-origin WebSocket URL: wss:// on https pages, ws:// otherwise. The telefly
 *  nginx reverse-proxies /ws/telefly to the backend, so the socket rides the page
 *  origin (telefly.<domain>) — no direct :8080 hop, no mixed-content block. */
function sameOriginWs(path: string): string {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}${path}`
}

export const config = {
  sessionToken: queryParam('sessionToken') ?? '',
  currency: queryParam('currency') ?? 'TON',
  operator: queryParam('operator') ?? '',
  /** Engine WebSocket endpoint; override with VITE_WS_URL. */
  wsUrl: import.meta.env.VITE_WS_URL ?? sameOriginWs('/ws/telefly'),
}
/**
 * Ask the host page (parent window) to open its deposit sheet for our currency —
 * called when a bet can't be covered. `depositUrl` is the parent's deep link, injected
 * into this page's query by the host page; we post a message so the sheet opens OVER the
 * game (cross-origin — no direct navigation). No-op when opened standalone.
 */
export function requestDeposit(): void {
  const url = new URLSearchParams(window.location.search).get('depositUrl') ?? ''
  try {
    window.parent?.postMessage({ type: 'openDeposit', currency: config.currency, url }, '*')
  } catch {
    // No parent / cross-origin block — nothing to do.
  }
}
