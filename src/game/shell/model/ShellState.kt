package game.shell.model

import kotlinx.serialization.Serializable
import model.Player
import util.Amount
import util.sha256

/**
 * The single response structure sent to the client on every command. One shape for
 * auth / start / pick, so the client always renders from the same payload and a
 * reload fully restores an in-progress round.
 *
 * [winningCup] is -1 until the round resolves; on WIN/LOSE it is the cup (0..2)
 * that holds the ball, which the client teleports the ball to before revealing.
 */
@Serializable
data class ShellState(
    val player: PlayerInfo,
    val state: State,
    val placeAmount: Long,
    val settleAmount: Long,
    val winningCup: Int,
    val fairness: Fairness
) {
    enum class State {
        /** No active round; waiting for `start`. */
        WAIT,

        /** Round open; waiting for the player's `pick`. */
        ACTIVE,

        /** Picked the winning cup — stake paid ×2. */
        WIN,

        /** Picked a wrong cup — stake lost. */
        LOSE
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
     * Provably-fair data for the in-game verifier. The winning cup is derived from
     * `HMAC-SHA256(serverSeed, "clientSeed:tonSeed:nonce")`.
     *
     * [serverSeedHash] is the up-front commitment; [serverSeed] is null while the
     * round is live and revealed once it resolves, so the player can recompute the
     * winning cup and confirm we never moved the ball.
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
            fun pending(clientSeed: String) = Fairness(serverSeedHash = "", clientSeed = clientSeed)

            fun committed(game: ShellGame) = Fairness(
                serverSeedHash = sha256(game.serverSeed),
                clientSeed = game.clientSeed,
                tonSeed = game.tonSeed,
                nonce = game.round.id.value
            )

            fun revealed(game: ShellGame) = committed(game).copy(serverSeed = game.serverSeed)
        }
    }

    companion object {
        private const val NO_CUP = -1

        /** No active round — only player info + the next round's client seed are meaningful. */
        fun wait(player: PlayerInfo, clientSeed: String) =
            ShellState(player, State.WAIT, 0, 0, NO_CUP, Fairness.pending(clientSeed))

        /** Active round restored/opened from [game]; the ball is not yet revealed. */
        fun active(game: ShellGame, player: PlayerInfo) =
            ShellState(player, State.ACTIVE, game.placeAmount.value, 0, NO_CUP, Fairness.committed(game))

        /** Correct pick — stake paid ×2; reveals the winning cup and the server seed. */
        fun win(game: ShellGame, player: PlayerInfo, winningCup: Int, settle: Long) =
            ShellState(player, State.WIN, game.placeAmount.value, settle, winningCup, Fairness.revealed(game))

        /** Wrong pick — stake lost; reveals where the ball actually was and the server seed. */
        fun lose(game: ShellGame, player: PlayerInfo, winningCup: Int) =
            ShellState(player, State.LOSE, game.placeAmount.value, 0, winningCup, Fairness.revealed(game))
    }
}
