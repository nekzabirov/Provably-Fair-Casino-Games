package game.mines.service

import util.sha256

/**
 * Deterministic mine placement from the provably-fair hash.
 *
 * Pure: no IO, no clock, no DB. Same (hash, mineCount) always yields the same
 * positions, which is what makes the round verifiable — after the round the
 * player recomputes the hash from the revealed serverSeed + TON clientSeed +
 * roundId and checks the layout matches.
 */
object MinesRng {

    const val TOTAL_CELLS = 25

    /**
     * Returns the set of mine cell indices in [0, 25) for the given hash.
     *
     * Uses a Fisher–Yates shuffle of all 25 cells driven by deterministic
     * integers drawn from the hash. The entropy pool is extended by re-hashing
     * if the 64-hex-char HMAC output is exhausted, so the shuffle is fully
     * reproducible regardless of how many draws are needed.
     */
    fun minePositions(hash: String, mineCount: Int): Set<Int> {
        require(mineCount in 3..(TOTAL_CELLS - 1)) {
            "mineCount must be between 3 and ${TOTAL_CELLS - 1}"
        }

        val cells = (0 until TOTAL_CELLS).toMutableList()
        val draw = HashIntSource(hash)

        for (i in cells.indices.reversed()) {
            val j = draw.nextInt(i + 1)
            val tmp = cells[i]; cells[i] = cells[j]; cells[j] = tmp
        }

        return cells.take(mineCount).toSet()
    }

    /** True if [index] is a mine for this hash/mineCount. */
    fun isMine(hash: String, mineCount: Int, index: Int): Boolean =
        index in minePositions(hash, mineCount)

    /**
     * Sequential deterministic integer source over a hex string, extended by
     * re-hashing when exhausted. 8 hex chars (32 bits) per draw.
     */
    private class HashIntSource(seed: String) {
        private var pool = seed
        private var cursor = 0

        fun nextInt(bound: Int): Int {
            if (cursor + 8 > pool.length) pool += sha256(pool)
            val slice = pool.substring(cursor, cursor + 8)
            cursor += 8
            return (slice.toLong(16) % bound).toInt()
        }
    }
}
