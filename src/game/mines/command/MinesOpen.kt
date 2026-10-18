package game.mines.command

import exception.RoundNotFoundException
import game.mines.model.MinesGame
import game.mines.model.MinesState
import game.mines.network.Connection
import game.mines.service.MinesRng
import kotlinx.serialization.Serializable
import service.ProvablyFair
import service.RoundService
import util.runTransactionCatching

/** open — reveal a cell. Mine -> FAIL + close; diamond -> ACTIVE. */
@Serializable
data class MinesOpen(val index: Int)

class MinesOpenHandler(
    private val roundService: RoundService,
    private val provablyFair: ProvablyFair
) {
    suspend fun handle(connection: Connection, command: MinesOpen) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val sessionToken = connection.requireToken()

        val state = runTransactionCatching {
            require(command.index in 0 until MinesRng.TOTAL_CELLS) { "index out of range" }

            val round = roundService.findActiveRound(player, MinesGame.GAME, currency)
                ?: throw RoundNotFoundException()
            val game = MinesGame.forRound(round) ?: throw RoundNotFoundException()
            require(command.index !in game.openedIndices()) { "cell already opened" }

            // Balance is stable during the round; reuse the value captured at start — no operator call.
            val info = MinesState.PlayerInfo.from(player, game.balance)

            val hash = provablyFair.computeHash(game.serverSeed, game.clientSeed, game.tonSeed, round.id.value.toString())

            if (MinesRng.isMine(hash, game.mineCount, command.index)) {
                roundService.close(player, sessionToken, MinesGame.GAME, round.currency).getOrThrow()
                MinesState.fail(game, info)
            } else {
                game.addOpened(command.index)
                MinesState.active(game, info)
            }
        }.getOrThrow()

        connection.send(state)
    }
}
