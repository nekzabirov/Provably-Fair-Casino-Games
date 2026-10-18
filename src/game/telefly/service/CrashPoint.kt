package game.telefly.service

import kotlin.math.pow

/**
 * Crash-point generation for TeleFly.
 *
 * Pure function, RTP baked in (like [game.mines.service.MinesMultiplier]). The
 * round's crash multiplier is derived from the provably-fair hash
 * (`HMAC-SHA256(serverSeed, clientSeed + roundNonce)`); the curve the plane flies
 * is just `exp(growthRate * t)` until it reaches this value.
 *
 * Standard bustabit-style distribution:
 *
 *   x = first 32 bits of the hash, normalised to [0, 1)
 *   crash = max(1.0, RTP / (1 - x))
 *
 * Properties (verified numerically — see plan's provably-fair check):
 *  - RTP at every cash-out target t is exactly [RTP] (house edge = 1 - RTP).
 *  - ~4% of rounds bust at 1.00x — this falls out of the clamp (when x < 1 - RTP,
 *    RTP/(1-x) < 1 and is clamped to 1.0). It is the house-edge mechanism, so do
 *    NOT add a separate instant-bust branch: that would double-tax the edge and
 *    drop the real RTP to ~0.92.
 *  - Long tail: x -> 1 produces very high multipliers.
 */
object CrashPoint {

    const val RTP = 0.96

    private const val HASH_BITS = 8          // 8 hex chars -> 32 bits
    private val SPACE = 2.0.pow(32)          // 2^32

    /** The round's crash multiplier (>= 1.00). */
    fun calculate(hash: String): Double {
        val h = hash.substring(0, HASH_BITS).toLong(16)
        val x = h / SPACE
        return maxOf(1.0, RTP / (1.0 - x))
    }
}
