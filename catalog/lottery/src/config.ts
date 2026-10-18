// Runtime config. sessionToken, currency and operator come from the page query params,
// e.g. /?sessionToken=BASE24...&currency=TON&operator=acme — the operator opens the
// client with these already set. token + operator authenticate every REST call (the
// session token is the bearer; no operator secret is ever exposed to the client).

function queryParam(name: string): string {
  return new URLSearchParams(window.location.search).get(name) ?? ''
}

export const config = {
  sessionToken: queryParam('sessionToken'),
  currency: queryParam('currency') || 'TON',
  operator: queryParam('operator'),
  /** Same-origin REST base; the lottery nginx proxies /api/v1 to the engine. */
  apiBase: import.meta.env.VITE_API_BASE ?? '',
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
