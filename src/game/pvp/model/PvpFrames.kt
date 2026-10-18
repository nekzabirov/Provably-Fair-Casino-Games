package game.pvp.model

import kotlinx.serialization.Serializable

/**
 * Global frames broadcast to every connected client by the engine (distinct from
 * the personal [PvpState]). Each carries a `type` discriminator the client switches
 * on. Serialized once per transition and fanned out to all sockets.
 */

/**
 * Phase / roster / pot snapshot. Broadcast on every membership or stake change and
 * on every phase transition, so all clients render the same wheel arcs, pot and
 * countdown. Never reveals the server seed (only its commitment hash).
 */
@Serializable
data class PvpPhaseFrame(
    val phase: Phase,
    val roundNonce: Long,
    val serverSeedHash: String,
    /** Epoch millis when the current phase ends (countdown lock); 0 when none. */
    val phaseEndsAt: Long,
    val pot: Long,
    val players: Int,
    val roster: List<PvpState.Seat>,
    val history: List<PvpState.HistoryItem> = emptyList(),
    val type: String = "phase"
)

/**
 * Round locked and decided. Reveals the server seed + winning number for
 * provably-fair verification; the client spins the wheel to [winningNumber] and
 * then celebrates the winner. The pot is paid in full to the winner ([payout] == [pot]).
 */
@Serializable
data class PvpSpinFrame(
    val winningNumber: Double,
    val winnerUsername: String,
    val winnerProfilePic: String? = null,
    val pot: Long,
    val payout: Long,
    val serverSeed: String,
    val clientSeed: String,
    val roundNonce: Long,
    /** Final ordered roster at lock — lets anyone reproduce the winning arc. */
    val roster: List<PvpState.Seat>,
    val type: String = "spin"
)
