package game.mines.service

/**
 * Classic casino Mines multiplier.
 *
 * Pure combinatorics, RTP baked in. After revealing `opened` diamonds with
 * `mineCount` mines on a 25-cell board:
 *
 *   multiplier = RTP * C(25, opened) / C(25 - mineCount, opened)
 *
 * Properties this gives us for free:
 *  - More mines  -> fewer safe cells -> higher multiplier (risk/reward).
 *  - Every reveal is individually fair at RTP.
 *  - 24 mines, 1 diamond revealed -> ~24.25x (matches design intent).
 */
object MinesMultiplier {

    const val RTP = 0.97
    const val TOTAL_CELLS = 25

    /** Multiplier after [opened] diamonds revealed. 0 opened -> 1.0 (just the stake). */
    fun calculate(mineCount: Int, opened: Int): Double {
        if (opened == 0) return 1.0
        val safe = TOTAL_CELLS - mineCount
        require(opened <= safe) { "opened ($opened) cannot exceed safe cells ($safe)" }
        return RTP * combination(TOTAL_CELLS, opened) / combination(safe, opened)
    }

    /** C(n, k) as Double, computed iteratively to avoid overflow. */
    private fun combination(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var result = 1.0
        for (i in 0 until k) {
            result = result * (n - i) / (i + 1)
        }
        return result
    }
}
