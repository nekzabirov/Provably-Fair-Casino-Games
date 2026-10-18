package game.lottery.model

import kotlinx.serialization.Serializable

/**
 * The public, un-authenticated jackpot frame pushed over `/ws/jackpot` to anyone (no
 * session). It describes the active draw — prize, window, status — so a brand can show
 * a live jackpot banner without a player session (for example, by bridging this
 * socket into its own backend stream).
 */
@Serializable
data class JackpotFrame(
    val type: String = "jackpot",

    /** The game/jackpot identity (always "lottery" here). */
    val identity: String = "lottery",

    /** Prize currency (TON). */
    val currency: String,

    /** When the current draw opened (epoch millis). */
    val startAt: Long,

    /** When sales close and the draw runs (epoch millis). */
    val endAt: Long,

    /** Current prize pool (seed + all sales), minor units. */
    val prizePool: Long,

    /** Draw lifecycle: SELLING | DRAWING | SETTLING | SETTLED. */
    val status: String
)
