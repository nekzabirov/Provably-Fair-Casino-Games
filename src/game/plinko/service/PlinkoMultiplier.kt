package game.plinko.service

/**
 * Plinko paytable. Pure data + lookups, RTP baked in (like
 * [game.mines.service.MinesMultiplier]).
 *
 * A ball falling through `rows` peg rows lands in slot `k` (the number of
 * right-bounces) with binomial probability `C(rows, k) / 2^rows`. The table gives
 * each slot's multiplier; tables are symmetric, so only the edge→center half is
 * stored and slot `k` reads `half[min(k, rows - k)]`.
 *
 * Every (risk, rows) table is tuned so that
 * `Σ C(rows,k)/2^rows × multiplier[k]` lands in [0.9650, 0.9720] — house edge ~3%,
 * matching Mines (0.97). Verified numerically when the tables were generated; the
 * shape follows the industry-standard curves (edges huge, center below 1.0, HIGH
 * risk far steeper than LOW).
 */
object PlinkoMultiplier {

    const val MIN_ROWS = 8
    const val MAX_ROWS = 16

    enum class Risk { LOW, MEDIUM, HIGH }

    /** The multiplier for landing in [slot] on a (risk, rows) board. */
    fun multiplier(risk: Risk, rows: Int, slot: Int): Double {
        val half = halfTable(risk, rows)
        require(slot in 0..rows) { "slot must be in 0..$rows" }
        return half[minOf(slot, rows - slot)]
    }

    /** The full multiplier row (rows + 1 slots, edge to edge) for display/verification. */
    fun multipliers(risk: Risk, rows: Int): List<Double> {
        val half = halfTable(risk, rows)
        return (0..rows).map { half[minOf(it, rows - it)] }
    }

    private fun halfTable(risk: Risk, rows: Int): DoubleArray {
        require(rows in MIN_ROWS..MAX_ROWS) { "rows must be in $MIN_ROWS..$MAX_ROWS" }
        return TABLES.getValue(risk).getValue(rows)
    }

    /** Edge→center halves per (risk, rows). Generated + RTP-verified; do not hand-edit. */
    private val TABLES: Map<Risk, Map<Int, DoubleArray>> = mapOf(
        Risk.LOW to mapOf(
            8 to doubleArrayOf(5.5, 2.1, 1.08, 0.98, 0.48),
            9 to doubleArrayOf(5.5, 1.96, 1.57, 0.98, 0.68),
            10 to doubleArrayOf(8.7, 2.9, 1.37, 1.08, 0.98, 0.49),
            11 to doubleArrayOf(8.2, 2.9, 1.86, 1.27, 0.98, 0.69),
            12 to doubleArrayOf(9.8, 2.9, 1.57, 1.37, 1.08, 0.98, 0.49),
            13 to doubleArrayOf(7.9, 3.9, 2.9, 1.86, 1.18, 0.88, 0.69),
            14 to doubleArrayOf(7.0, 3.9, 1.86, 1.37, 1.27, 1.08, 0.98, 0.49),
            15 to doubleArrayOf(14.7, 7.8, 2.9, 1.96, 1.47, 1.08, 0.98, 0.69),
            16 to doubleArrayOf(15.7, 8.8, 1.96, 1.37, 1.37, 1.18, 1.08, 0.98, 0.49)
        ),
        Risk.MEDIUM to mapOf(
            8 to doubleArrayOf(12.7, 2.9, 1.27, 0.69, 0.39),
            9 to doubleArrayOf(17.6, 3.9, 1.66, 0.88, 0.49),
            10 to doubleArrayOf(22.0, 4.9, 1.96, 1.37, 0.59, 0.39),
            11 to doubleArrayOf(24.0, 5.9, 2.9, 1.76, 0.69, 0.49),
            12 to doubleArrayOf(32.0, 10.8, 3.9, 1.96, 1.08, 0.59, 0.29),
            13 to doubleArrayOf(42.0, 12.7, 5.9, 2.9, 1.27, 0.69, 0.39),
            14 to doubleArrayOf(57.0, 14.7, 6.9, 3.9, 1.86, 0.98, 0.49, 0.2),
            15 to doubleArrayOf(86.0, 17.6, 10.8, 4.9, 2.9, 1.27, 0.49, 0.3),
            16 to doubleArrayOf(108.0, 40.0, 9.8, 4.9, 2.9, 1.47, 0.98, 0.49, 0.29)
        ),
        Risk.HIGH to mapOf(
            8 to doubleArrayOf(28.0, 3.9, 1.47, 0.29, 0.2),
            9 to doubleArrayOf(42.0, 6.9, 1.96, 0.59, 0.19),
            10 to doubleArrayOf(74.0, 9.8, 2.9, 0.88, 0.29, 0.2),
            11 to doubleArrayOf(117.0, 13.7, 5.1, 1.37, 0.39, 0.19),
            12 to doubleArrayOf(166.0, 23.0, 7.9, 1.96, 0.69, 0.2, 0.2),
            13 to doubleArrayOf(255.0, 36.0, 10.8, 3.9, 0.98, 0.2, 0.19),
            14 to doubleArrayOf(412.0, 55.0, 17.6, 4.9, 1.86, 0.29, 0.2, 0.2),
            15 to doubleArrayOf(607.0, 81.0, 26.0, 7.8, 2.9, 0.49, 0.2, 0.2),
            16 to doubleArrayOf(980.0, 127.0, 25.0, 8.8, 3.9, 1.96, 0.2, 0.2, 0.2)
        )
    )
}
