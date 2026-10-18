package game.mines.command

import exception.RoundNotFoundException
import game.mines.model.MinesGame
import game.mines.model.MinesState
import game.mines.network.Connection
import kotlinx.serialization.Serializable
import service.PlayerService
import service.RoundService
import util.Amount
import util.runTransactionCatching

/** cashout — credit current winnings, close round, return WAIT. No payload. */
@Serializable
class MinesCashout

class MinesCashoutHandler(
    private val roundService: RoundService,
    private val playerService: PlayerService
) {
    suspend fun handle(connection: Connection, command: MinesCashout) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val sessionToken = connection.requireToken()

        val (game, settle) = runTransactionCatching {
            val round = roundService.findActiveRound(player, MinesGame.GAME, currency)
                ?: throw RoundNotFoundException()
            val game = MinesGame.forRound(round) ?: throw RoundNotFoundException()
            game to Amount(game.settleAmount())
        }.getOrThrow()

        // Credit in its own flow: a lost webhook leaves a PENDING row whose id a
        // retried cashout replays — the operator deduplicates instead of paying twice.
        roundService.credit(player, sessionToken, MinesGame.GAME, currency, settle).getOrThrow()

        val state = runTransactionCatching {
            roundService.close(player, sessionToken, MinesGame.GAME, currency).getOrThrow()

            // Fetch balance so the client sees the credited result.
            val balance = playerService.findBalance(player, sessionToken, currency).getOrThrow()
            MinesState.revealedWait(game, MinesState.PlayerInfo.from(player, balance))
        }.getOrThrow()

        connection.send(state)
    }
}
