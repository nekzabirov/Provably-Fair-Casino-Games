package game.pvp.model

import kotlinx.serialization.Serializable
import model.Player
import util.Amount

/** The round phase, shared by the engine, the personal state, and the broadcast frames. */
enum class Phase {
    /** Fewer than the minimum players have a stake — bets open, no timer. */
    WAITING,

    /** Enough players — a countdown to lock runs; bets still open (join/top-up/cancel). */
    COUNTDOWN,

    /** Bets locked; the winner is decided and the wheel is animating to the number. */
    SPINNING,

    /** Brief pause showing the winner before the next round opens. */
    PAYOUT
}

/**
 * Personal reply sent to a single connection on auth / bet / cancel. Carries the
 * live phase, the shared roster + pot, and this player's own stake, so a reconnect
 * fully restores the player's view. Roster/pot/phase changes also arrive to
 * everyone as a [PvpPhaseFrame]; the spin outcome arrives as a [PvpSpinFrame].
 */
@Serializable
data class PvpState(
    val type: String = "state",
    val player: PlayerInfo,
    val phase: Phase,
    val roundNonce: Long,
    val serverSeedHash: String,
    /** Epoch millis when the current phase ends (countdown lock / spin end); 0 when none. */
    val phaseEndsAt: Long,
    /** Total pooled stake this round, minor units. */
    val pot: Long,
    /** Number of players with a stake this round. */
    val players: Int,
    /** Every participant's seat, in canonical (join) order — drives the wheel arcs. */
    val roster: List<Seat>,
    /** This player's own stake this round, or null if they have not bet. */
    val bet: BetView? = null,
    val history: List<HistoryItem> = emptyList()
) {
    @Serializable
    data class PlayerInfo(
        val username: String,
        val balance: Long,
        val profilePic: String? = null
    ) {
        companion object {
            fun from(player: Player, balance: Amount) =
                PlayerInfo(player.username, balance.value, player.profilePic)
        }
    }

    /** One participant's arc. [order] is the within-round index (0-based), stable for the round. */
    @Serializable
    data class Seat(
        val order: Int,
        val username: String,
        val stake: Long,
        val profilePic: String? = null
    )

    enum class BetStatus { PLACED, WON, LOST }

    @Serializable
    data class BetView(
        val order: Int,
        val stake: Long,
        val status: BetStatus
    )

    /** A past round's outcome, newest last. */
    @Serializable
    data class HistoryItem(
        val winningNumber: Double,
        val winnerUsername: String,
        val pot: Long
    )
}
