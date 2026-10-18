// Provably-fair verification, run entirely in the browser. Mirrors the engine's
// ProvablyFair + CrashPoint so a player can reproduce a crashed round's multiplier
// from the revealed seeds.
//
// TeleFly is a SHARED real-time round: one crash point serves every bettor at once,
// so a per-player client seed is impossible. Its public seed is a TON block hash —
// a value neither the house nor any player can predict. We commit to the server
// seed (its SHA-256 hash is broadcast during betting) and reveal it on crash.
//
//   hash  = HMAC-SHA256(serverSeed, clientSeed + nonce)   // clientSeed = TON block hash
//   crash = max(1.00, 0.96 / (1 - first32bits/2^32))
//   commit = SHA-256(serverSeed)  ← shown before the round; must match after

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

/** The round's outcome hash (shared-game form: no separators, matches the engine). */
export function computeHash(serverSeed: string, clientSeed: string, nonce: number | string): Promise<string> {
  return hmacSha256Hex(serverSeed, `${clientSeed}${nonce}`)
}

const RTP = 0.96
const SPACE = 2 ** 32

/** The crash multiplier (>= 1.00) for a hash. Ports CrashPoint exactly. */
export function crashPoint(hash: string): number {
  const x = parseInt(hash.slice(0, 8), 16) / SPACE
  return Math.max(1, RTP / (1 - x))
}
