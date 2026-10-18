package game.telefly.model

import kotlinx.serialization.Serializable
import model.Player
import util.Amount

/** The round phase, shared by the engine, the personal state, and the broadcast frames. */
enum class Phase { BETTING, FLYING, CRASHED }

/**
 * Personal reply sent to a single connection on auth / bet / cashout. Carries the
 * live phase, this player's bet (if any), and their balance, so a reconnect fully
 * restores the player's view of the current round. Global multiplier ticks arrive
 * separately as [TickFrame]; this only snapshots the multiplier at reply time.
 *
 * Every server frame carries an explicit [type] discriminator (the client receives
 * several frame shapes, unlike Mines' single shape).
 */
@Serializable
data class TeleFlyState(
    val type: String = "state",
    val player: PlayerInfo,
    val phase: Phase,
    val serverSeedHash: String,
    val multiplier: Double,
    /** Epoch millis when the current phase ends — restores the betting countdown on reconnect. */
    val phaseEndsAt: Long,
    /** Number of bets in the current round (the live players counter). */
    val players: Int = 0,
    val bet: BetView? = null,
    val history: List<Double> = emptyList()
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

    enum class BetStatus {
        /** Stake debited, in the round, not yet cashed. */
        PLACED,

        /** Cashed out — won [cashoutMultiplier]. */
        CASHED,

        /** Round crashed before cashout — stake lost. */
        LOST
    }

    @Serializable
    data class BetView(
        val placeAmount: Long,
        val cashoutMultiplier: Double? = null,
        val settleAmount: Long,
        val status: BetStatus
    )
}
