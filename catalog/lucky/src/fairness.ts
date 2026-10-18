// Provably-fair verification, run entirely in the browser. Mirrors the engine's
// ProvablyFair + ShellResult so a player can reproduce the winning cup from the
// revealed seeds and confirm we never moved the ball.
//
//   hash       = HMAC-SHA256(serverSeed, "clientSeed:tonSeed:nonce")
//   winningCup = first 32 bits of the hash, mod 3
//   commit     = SHA-256(serverSeed)  ← shown before the round; must match after

const enc = new TextEncoder()

function toHex(buf: ArrayBuffer): string {
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

/** SHA-256 of a string, hex. Matches the engine's `sha256(serverSeed)` commitment. */
export async function sha256Hex(input: string): Promise<string> {
  return toHex(await crypto.subtle.digest('SHA-256', enc.encode(input)))
}

async function hmacSha256Hex(key: string, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    'raw',
    enc.encode(key),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  )
  return toHex(await crypto.subtle.sign('HMAC', cryptoKey, enc.encode(message)))
}

/** The round's outcome hash. */
export function computeHash(
  serverSeed: string,
  clientSeed: string,
  tonSeed: string,
  nonce: number | string,
): Promise<string> {
  return hmacSha256Hex(serverSeed, `${clientSeed}:${tonSeed}:${nonce}`)
}

export const CUPS = 3

/** The cup (0..2) that holds the ball for this hash. Ports ShellResult exactly. */
export function winningCup(hash: string): number {
  return parseInt(hash.slice(0, 8), 16) % CUPS
}

// ── Client seed store ─────────────────────────────────────────────────────────

const CLIENT_SEED_KEY = 'luckycup.clientSeed'

function randomSeed(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return toHex(bytes.buffer)
}

export function normalizeSeed(raw: string): string {
  const trimmed = raw.trim()
  return /^[A-Za-z0-9-_]{1,64}$/.test(trimmed) ? trimmed : ''
}

export function loadClientSeed(): string {
  try {
    const saved = normalizeSeed(localStorage.getItem(CLIENT_SEED_KEY) ?? '')
    if (saved) return saved
  } catch {
    /* ignore */
  }
  const fresh = randomSeed()
  saveClientSeed(fresh)
  return fresh
}

export function saveClientSeed(seed: string): void {
  try {
    localStorage.setItem(CLIENT_SEED_KEY, seed)
  } catch {
    /* ignore */
  }
}

export function freshClientSeed(): string {
  return randomSeed()
}
