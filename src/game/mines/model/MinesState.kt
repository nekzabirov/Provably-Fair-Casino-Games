package game.mines.model

import kotlinx.serialization.Serializable
import model.Player
import util.Amount
import util.sha256

/**
 * The single response structure sent to the client on every command. One shape for
 * auth / start / open / cashout, so the client always renders from the same payload
 * and a reload fully restores an in-progress round.
 */
@Serializable
data class MinesState(
    val player: PlayerInfo,
    val state: State,
    val placeAmount: Long,
    val settleAmount: Long,
    val mineCount: Int,
    val cells: List<Cell>,
    val fairness: Fairness
) {
    enum class State {
        /** Round open; player may open more cells or cash out. */
        ACTIVE,

        /** No active round; waiting for `start`. */
        WAIT,

        /** Round ended on a mine; player lost the stake. */
        FAIL
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

    @Serializable
    data class Cell(
        val index: Int,
        val type: Type,
        /** Cumulative multiplier at the moment this diamond was revealed. */
        val multiplier: Double
    ) {
        enum class Type { DIAMOND, MINE }
    }

    /**
     * Provably-fair data for the in-game verifier. The mine layout is derived from
     * `HMAC-SHA256(serverSeed, "clientSeed:tonSeed:nonce")`.
     *
     * [serverSeedHash] is the up-front commitment; [serverSeed] is null while the
     * round is live and only revealed once it closes, so the player can recompute
     * and confirm we never moved the mines.
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
            /** No active round: only the client seed the next round will use is known. */
            fun pending(clientSeed: String) = Fairness(serverSeedHash = "", clientSeed = clientSeed)

            /** Live round: commitment + inputs shared; server seed withheld until close. */
            fun committed(game: MinesGame) = Fairness(
                serverSeedHash = sha256(game.serverSeed),
                clientSeed = game.clientSeed,
                tonSeed = game.tonSeed,
                nonce = game.round.id.value
            )

            /** Closed round: full reveal so the layout can be reproduced. */
            fun revealed(game: MinesGame) = committed(game).copy(serverSeed = game.serverSeed)
        }
    }

    companion object {
        /** No active round — only player info + the next round's client seed are meaningful. */
        fun wait(player: PlayerInfo, clientSeed: String) =
            MinesState(player, State.WAIT, 0, 0, 0, emptyList(), Fairness.pending(clientSeed))

        /** Cashed out: no active round, but reveal the finished round's seed + mine count for verification. */
        fun revealedWait(game: MinesGame, player: PlayerInfo) =
            MinesState(player, State.WAIT, 0, 0, game.mineCount, emptyList(), Fairness.revealed(game))

        /** Active round restored/advanced from [game]. */
        fun active(game: MinesGame, player: PlayerInfo) =
            MinesState(
                player = player,
                state = State.ACTIVE,
                placeAmount = game.placeAmount.value,
                settleAmount = game.settleAmount(),
                mineCount = game.mineCount,
                cells = game.revealedCells(),
                fairness = Fairness.committed(game)
            )

        /** Round lost on a mine — reveals only what the player opened, plus the server seed. */
        fun fail(game: MinesGame, player: PlayerInfo) =
            MinesState(
                player = player,
                state = State.FAIL,
                placeAmount = game.placeAmount.value,
                settleAmount = 0,
                mineCount = game.mineCount,
                cells = game.revealedCells(),
                fairness = Fairness.revealed(game)
            )
    }
}
