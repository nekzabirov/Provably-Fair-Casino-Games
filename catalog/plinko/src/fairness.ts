// Provably-fair verification, run entirely in the browser. Mirrors the engine's
// ProvablyFair + PlinkoPath so a player can reproduce a drop from the revealed
// seeds and confirm the path was fixed before the ball fell.
//
//   hash = HMAC-SHA256(serverSeed, "clientSeed:tonSeed:nonce")
//   row i bounce = lowest bit of hex digit i  (0 = left, 1 = right)
//   slot = number of right-bounces
//   commit = SHA-256(serverSeed) — must match the hash shown with the result

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

/** The drop's outcome hash. */
export function computeHash(
  serverSeed: string,
  clientSeed: string,
  tonSeed: string,
  nonce: number | string,
): Promise<string> {
  return hmacSha256Hex(serverSeed, `${clientSeed}:${tonSeed}:${nonce}`)
}

/** The bounce sequence (0/1 per row) for a hash. Ports PlinkoPath exactly. */
export function pathBits(hash: string, rows: number): number[] {
  return Array.from({ length: rows }, (_, i) => parseInt(hash[i], 16) & 1)
}

/** The landing slot = number of right-bounces. */
export function slotOf(hash: string, rows: number): number {
  return pathBits(hash, rows).reduce((a, b) => a + b, 0)
}

// ── Client seed store ─────────────────────────────────────────────────────────
// The player's seed persists across drops in localStorage and is sent on every
// auth. Changing it reconnects so the next drop uses the new seed.

const CLIENT_SEED_KEY = 'plinko.clientSeed'

function randomSeed(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return toHex(bytes.buffer)
}

/** Sanitises a candidate seed to the engine's charset/length; '' → falsy. */
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
