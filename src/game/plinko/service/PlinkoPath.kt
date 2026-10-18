package game.plinko.service

/**
 * Deterministic ball path from the provably-fair hash.
 *
 * Pure: no IO, no clock, no DB. Row `i` of the fall reads hex digit `i` of the
 * hash; its lowest bit decides the bounce (0 = left, 1 = right). The landing slot
 * is simply the number of right-bounces. A 64-hex-char HMAC output covers the
 * maximum 16 rows with room to spare, so no pool extension is ever needed — which
 * keeps the in-app verifier a one-liner.
 */
object PlinkoPath {

    /** The bounce sequence (0/1 per row) for this hash. */
    fun path(hash: String, rows: Int): List<Int> =
        (0 until rows).map { hash[it].digitToInt(16) and 1 }

    /** The landing slot (0..rows) = number of right-bounces. */
    fun slot(hash: String, rows: Int): Int = path(hash, rows).sum()
}
