package game.shell.service

/**
 * Deterministic 3-cup outcome from the provably-fair hash.
 *
 * Pure: no IO, no clock, no DB. The cup that hides the ball is fixed up-front from
 * (serverSeed + clientSeed + roundId) — the same hash the player can recompute
 * after the round to verify the result. The player wins only by picking that exact
 * cup (true 1/3, ×2 payout).
 */
object ShellResult {

    const val CUPS = 3

    /** The cup (0..2) that holds the ball for this hash. */
    fun winningCup(hash: String): Int = (hash.substring(0, 8).toLong(16) % CUPS).toInt()

    /** True if the player's chosen cup matches the winning cup. */
    fun isWin(hash: String, chosenCup: Int): Boolean = chosenCup == winningCup(hash)
}
