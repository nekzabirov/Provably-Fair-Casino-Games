package game.lottery.model

import kotlinx.serialization.Serializable

/**
 * The full snapshot the engine exposes for ONE player — over the per-player socket and
 * the REST `state`/`buy` replies. A player holds at most [maxTickets] (one) system
 * ticket this draw: [minPicks]..[maxPicks] numbers covering C(n, minPicks) combinations,
 * priced quadratically: [ticketPrice] × (n − minPicks + 1)².
 *
 * The personal fields ([tickets], [ticketCount], [winChance]) reflect THIS player; the
 * rest are shared by everyone. [winChance] is the probability of the draw landing
 * inside the held ticket — combinations covered / C(range, picks) — in [0, 1]; the
 * client renders it as a percentage.
 */
@Serializable
data class LotteryState(
    val type: String = "lottery",

    /** Current prize pool (seed + all ticket sales), minor units. */
    val prizePool: Long,

    /** Always the lottery currency (TON). */
    val currency: String,

    /** Price of a minimum (6-number) ticket, minor units. An n-number ticket costs
     *  (n − minPicks + 1)² times this. */
    val ticketPrice: Long,

    /** This player's tickets for the current draw (empty if none). */
    val tickets: List<LotteryTicketDto>,

    /** How many tickets this player holds this draw (= tickets.size). */
    val ticketCount: Int,

    /** Max tickets one player may buy per draw. */
    val maxTickets: Int,

    /** Minimum numbers on a ticket (= how many the draw reveals). */
    val minPicks: Int,

    /** Maximum numbers on a system ticket. */
    val maxPicks: Int,

    /** Probability this player wins the jackpot across all held tickets, in [0, 1]. */
    val winChance: Double,

    /** When the current draw opened (epoch millis). */
    val startsAt: Long,

    /** When sales close and the draw runs (epoch millis). */
    val endsAt: Long,

    /** Winning numbers revealed so far (empty until the draw; grows to the full set). */
    val winningNumbers: List<Int>,

    /** Whether buying a ticket is currently allowed. */
    val bettingOpen: Boolean,

    /** Whether the current draw has finished (winners decided, pool paid). */
    val finished: Boolean,

    /** The nonce mixed into the draw hash (= the draw id); lets a client recompute the numbers. */
    val drawNonce: Long,

    /** Commitment to the server seed, shown the whole week so the draw can't be retargeted. */
    val serverSeedHash: String,

    /** Revealed only once [finished] — lets anyone recompute the draw. */
    val serverSeed: String? = null,

    /** The public TON seed mixed into the draw, revealed once [finished]. */
    val tonSeed: String? = null
)

/** One of the player's tickets for the current draw. */
@Serializable
data class LotteryTicketDto(
    /** Stable ticket id (rendered as the human "Ticket #…"). */
    val id: Long,

    /** The chosen numbers (6..maxPicks, sorted). */
    val numbers: List<Int>,

    /** Whether this ticket holds all drawn numbers, once the draw is decided (null before). */
    val matched: Boolean? = null,

    /** This ticket's winnings, minor units, once decided (null before / if not a winner). */
    val payout: Long? = null
)
