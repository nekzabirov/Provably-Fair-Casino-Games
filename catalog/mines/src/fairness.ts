// Provably-fair verification, run entirely in the browser. Mirrors the engine's
// ProvablyFair + MinesRng so a player can independently reproduce the mine layout
// from the revealed seeds and confirm we never moved a mine.
//
//   hash   = HMAC-SHA256(serverSeed, "clientSeed:tonSeed:nonce")
//   mines  = Fisher–Yates shuffle of 25 cells driven by the hash (first mineCount)
//   commit = SHA-256(serverSeed)  ← shown before the round; must match after
//
// The HMAC key and message are the UTF-8 bytes of the seed strings (not decoded
// hex), exactly as the JVM side does it.

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

/** HMAC-SHA256(key, message), hex. */
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

const TOTAL_CELLS = 25

/**
 * Mine cell indices for a hash + mine count. Ports MinesRng exactly: a sequential
 * 32-bit integer source over the hex hash, re-hashed (SHA-256 of the whole pool)
 * whenever it runs dry, drives a Fisher–Yates shuffle; the first `mineCount` cells
 * are the mines.
 */
export async function minePositions(hash: string, mineCount: number): Promise<number[]> {
  // Each of the 25 draws consumes 8 hex chars, so pre-grow the pool to cover them
  // (the engine grows lazily; the resulting byte stream is identical either way).
  let pool = hash
  while (pool.length < TOTAL_CELLS * 8) pool += await sha256Hex(pool)

  let cursor = 0
  const nextInt = (bound: number): number => {
    const slice = pool.slice(cursor, cursor + 8)
    cursor += 8
    return parseInt(slice, 16) % bound
  }

  const cells = Array.from({ length: TOTAL_CELLS }, (_, i) => i)
  for (let i = cells.length - 1; i >= 0; i--) {
    const j = nextInt(i + 1)
    ;[cells[i], cells[j]] = [cells[j], cells[i]]
  }
  return cells.slice(0, mineCount).sort((a, b) => a - b)
}

// ── Client seed store ─────────────────────────────────────────────────────────
// The player's seed persists across rounds in localStorage and is sent on every
// auth. Changing it reconnects so the next round uses the new seed.

const CLIENT_SEED_KEY = 'mines.clientSeed'

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
