package game.plinko.model

import game.plinko.service.PlinkoPath
import kotlinx.serialization.Serializable
import model.Player
import util.Amount
import util.sha256

/**
 * The single response structure sent to the client on every command. One shape for
 * auth / drop, so the client always renders from the same payload.
 *
 * Plinko is instant, so there is no in-progress round to restore: auth always
 * answers WAIT, and every drop answers RESULT with the full outcome — including
 * the [path] the client animates the ball along.
 */
@Serializable
data class PlinkoState(
    val player: PlayerInfo,
    val state: State,
    val rows: Int,
    val risk: String,
    val placeAmount: Long,
    val settleAmount: Long,
    /** Landing slot (0..rows); -1 while WAIT. */
    val slot: Int,
    val multiplier: Double,
    /** Bounce per row: 0 = left, 1 = right. Empty while WAIT. */
    val path: List<Int>,
    val fairness: Fairness
) {
    enum class State {
        /** No drop in flight; waiting for `drop`. */
        WAIT,

        /** A drop resolved — the full outcome is in this frame. */
        RESULT
    }

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

    /**
     * Provably-fair data for the in-game verifier. The path is derived from
     * `HMAC-SHA256(serverSeed, "clientSeed:tonSeed:nonce")` — one hex digit per
     * row, lowest bit = bounce direction.
     *
     * Plinko resolves instantly, so the commitment and the reveal arrive in the
     * same frame: [serverSeedHash] proves the seed was fixed before the outcome
     * was computed, and [serverSeed] lets the player reproduce it on the spot.
     */
    @Serializable
    data class Fairness(
        val serverSeedHash: String,
        val clientSeed: String,
        val tonSeed: String? = null,
        val nonce: Long? = null,
        val serverSeed: String? = null
    ) {
        companion object {
            /** No drop yet: only the client seed the next drop will use is known. */
            fun pending(clientSeed: String) = Fairness(serverSeedHash = "", clientSeed = clientSeed)

            /** Resolved drop: full reveal so the path can be reproduced. */
            fun revealed(game: PlinkoGame) = Fairness(
                serverSeedHash = sha256(game.serverSeed),
                clientSeed = game.clientSeed,
                tonSeed = game.tonSeed,
                nonce = game.fairnessNonce(),
                serverSeed = game.serverSeed
            )
        }
    }

    companion object {
        /** Idle — only player info + the next drop's client seed are meaningful. */
        fun wait(player: PlayerInfo, clientSeed: String) =
            PlinkoState(player, State.WAIT, 0, "", 0, 0, -1, 0.0, emptyList(), Fairness.pending(clientSeed))

        /** A resolved drop, with the path recomputed from the stored seeds. */
        fun result(game: PlinkoGame, player: PlayerInfo, hash: String) =
            PlinkoState(
                player = player,
                state = State.RESULT,
                rows = game.rows,
                risk = game.risk.name,
                placeAmount = game.placeAmount.value,
                settleAmount = game.settleAmount(),
                slot = game.slot,
                multiplier = game.multiplier,
                path = PlinkoPath.path(hash, game.rows),
                fairness = Fairness.revealed(game)
            )
    }
}
