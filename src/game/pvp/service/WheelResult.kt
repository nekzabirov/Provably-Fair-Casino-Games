package game.pvp.service

import kotlin.math.pow

/**
 * Jackpot-wheel outcome from the provably-fair hash.
 *
 * Pure function (like [game.telefly.service.CrashPoint]). The round's winning
 * number is derived from `HMAC-SHA256(serverSeed, tonSeed + roundNonce)`; the wheel
 * the client spins just lands its pointer on that number.
 *
 *   x       = first 32 bits of the hash, normalised to [0, 1)
 *   number  = x * 100                      → the 0..100 wheel number (the center value)
 *   ticket  = floor(x * pot)               → a minor-unit index into the pooled stake
 *   winner  = the player whose cumulative [start, end) stake interval contains ticket,
 *             players taken in canonical join order (the order the roster is sent in)
 *
 * Because each player's arc is drawn proportional to their stake in that same order,
 * the pointer at angle `x * 360°` lands in exactly the winning arc — the visual and
 * the math agree by construction. Probability of winning = stake / pot, so the pot
 * is paid out in full with zero house edge.
 */
object WheelResult {

    private const val HASH_BITS = 8          // 8 hex chars -> 32 bits
    private val SPACE = 2.0.pow(32)          // 2^32

    /** The hash normalised to [0, 1). */
    fun fraction(hash: String): Double {
        val h = hash.substring(0, HASH_BITS).toLong(16)
        return h / SPACE
    }

    /** The 0..100 wheel number shown in the center / used for the pointer angle. */
    fun winningNumber(fraction: Double): Double = fraction * 100.0

    /**
     * Index of the winning player given their stakes in canonical (roster) order.
     * [orderedStakes] must be non-empty with a positive total.
     */
    fun winner(fraction: Double, orderedStakes: List<Long>): Int {
        val pot = orderedStakes.sum()
        require(pot > 0L) { "pot must be positive" }
        // ticket in [0, pot); clamp guards the fraction==~1.0 upper edge.
        val ticket = (fraction * pot).toLong().coerceIn(0L, pot - 1L)
        var cumulative = 0L
        for (i in orderedStakes.indices) {
            cumulative += orderedStakes[i]
            if (ticket < cumulative) return i
        }
        return orderedStakes.size - 1
    }
}
