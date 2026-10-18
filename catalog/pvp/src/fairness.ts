// Provably-fair verification, run entirely in the browser. Mirrors the engine's
// ProvablyFair (3-arg shared form) + WheelResult so a player can reproduce a
// round's winning number - and the winning player - from the revealed seeds.
//
// PvP is a SHARED round: one outcome serves everyone, so there is no per-player
// client seed. The public seed is a TON block hash. We commit to the server seed
// (its SHA-256 is broadcast while bets are open) and reveal it in the spin frame.
//
//   hash    = HMAC-SHA256(serverSeed, tonSeed + nonce)   // tonSeed == clientSeed
//   fraction = first 32 bits of hash / 2^32  ∈ [0,1)
//   number   = fraction × 100                            // the center / pointer value
//   ticket   = floor(fraction × pot)
//   winner   = the seat whose cumulative [start,end) stake interval contains ticket
//   commit   = SHA-256(serverSeed) - must match the hash shown while betting

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

/** The round's outcome hash (shared form: no separators, matches the engine's 3-arg computeHash). */
export function computeHash(serverSeed: string, clientSeed: string, nonce: number | string): Promise<string> {
  return hmacSha256Hex(serverSeed, `${clientSeed}${nonce}`)
}

const SPACE = 2 ** 32

/** The hash normalised to [0,1). */
export function fraction(hash: string): number {
  return parseInt(hash.slice(0, 8), 16) / SPACE
}

/** The 0..100 wheel number. */
export function winningNumber(f: number): number {
  return f * 100
}

/** Index of the winning seat given stakes in roster (canonical) order. Ports WheelResult.winner. */
export function winnerIndex(f: number, orderedStakes: number[]): number {
  const pot = orderedStakes.reduce((a, b) => a + b, 0)
  if (pot <= 0) return -1
  const ticket = Math.min(Math.floor(f * pot), pot - 1)
  let cumulative = 0
  for (let i = 0; i < orderedStakes.length; i++) {
    cumulative += orderedStakes[i]
    if (ticket < cumulative) return i
  }
  return orderedStakes.length - 1
}
