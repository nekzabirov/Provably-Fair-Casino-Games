package game.lottery.service

import util.sha256

/**
 * Deterministic winning-number draw from the provably-fair hash.
 *
 * Pure: no IO, no clock, no DB. The same hash always yields the same set, which is
 * what makes the draw verifiable — after the round the player recomputes the hash
 * from the revealed serverSeed + public TON seed + lotteryId and checks the numbers
 * match.
 *
 * Draws [count] DISTINCT numbers in `[1, range]` (a 6-of-49 ticket) via a partial
 * Fisher–Yates shuffle driven by deterministic integers from the hash (mirrors
 * [game.mines.service.MinesRng]). Only [count] integers are drawn (8 hex chars each),
 * so the 64-hex-char HMAC output covers the default 6-of-49 draw without re-hashing.
 * Result is sorted — the match is on the set, order is irrelevant.
 */
object LotteryDraw {

    fun numbers(hash: String, count: Int, range: Int): List<Int> {
        require(count in 1..range) { "count must be in 1..$range" }

        val pool = (1..range).toMutableList()
        val draw = HashIntSource(hash)
        val n = pool.size
        for (k in 0 until count) {
            val i = n - 1 - k
            val j = draw.nextInt(i + 1)
            val tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp
        }
        return pool.takeLast(count).sorted()
    }

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
