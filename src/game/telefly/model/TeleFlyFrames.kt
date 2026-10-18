package game.telefly.model

import kotlinx.serialization.Serializable

/**
 * Global frames broadcast to every connected client by the engine (distinct from
 * the personal [TeleFlyState]). Each carries a `type` discriminator the client
 * switches on. Serialized once per tick/transition and fanned out to all sockets.
 */

/** Phase transition. Sent on BETTING / FLYING / CRASHED entry. Never reveals crashMultiplier. */
@Serializable
data class PhaseFrame(
    val phase: Phase,
    val roundNonce: Long,
    val serverSeedHash: String,
    /** Epoch millis when the current phase ends (betting close / expected crash). */
    val phaseEndsAt: Long,
    /** Number of bets in the current round (the live players counter). */
    val players: Int = 0,
    val history: List<Double> = emptyList(),
    val type: String = "phase"
)

/** Live multiplier during FLYING (~10/s). Money-free and DB-free. */
@Serializable
data class TickFrame(
    val multiplier: Double,
    val type: String = "tick"
)

/** Round crashed. Reveals the seed + crash point for provably-fair verification. */
@Serializable
data class CrashFrame(
    val crashMultiplier: Double,
    val serverSeed: String,
    val clientSeed: String,
    val roundNonce: Long,
    val type: String = "crash"
)

/** Live feed: a player placed a bet. */
@Serializable
data class BetPlacedFrame(
    val username: String,
    val amount: Long,
    val currency: String,
    val players: Int = 0,
    val profilePic: String? = null,
    val type: String = "betPlaced"
)

/** Live feed: a player cashed out. */
@Serializable
data class BetCashoutFrame(
    val username: String,
    val multiplier: Double,
    val payout: Long,
    val currency: String,
    val profilePic: String? = null,
    val type: String = "betCashout"
)
