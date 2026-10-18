package game.mines.command

import game.mines.model.MinesGame
import game.mines.model.MinesState
import game.mines.network.Connection
import kotlinx.serialization.Serializable
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Amount
import util.runTransactionCatching

/** start — open round + debit; currency comes from the connection. */
@Serializable
data class MinesStart(val amount: Long, val mineCount: Int)

class MinesStartHandler(
    private val roundService: RoundService,
    private val provablyFair: ProvablyFair,
    private val playerService: PlayerService
) {
    suspend fun handle(connection: Connection, command: MinesStart) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val sessionToken = connection.requireToken()

        // The round commits BEFORE the stake moves: the debit runs in its own
        // transactions and must reference a durable round.
        val (round, resumed) = runTransactionCatching {
            require(command.amount > 0) { "amount must be positive" }
            require(command.mineCount in 3..24) { "mineCount must be 3..24" }

            val round = roundService.open(player, sessionToken, MinesGame.GAME, currency).getOrThrow()

            // Idempotent: a re-sent `start` (double click) returns the in-progress
            // round's state WITHOUT debiting again, instead of double-charging.
            val resumed = MinesGame.forRound(round)?.let { existing ->
                val current = playerService.findBalance(player, sessionToken, currency).getOrThrow()
                MinesState.active(existing, MinesState.PlayerInfo.from(player, current))
            }
            round to resumed
        }.getOrThrow()

        if (resumed != null) {
            connection.send(resumed)
            return
        }

        val debit = roundService.debit(player, sessionToken, MinesGame.GAME, currency, Amount(command.amount)).getOrThrow()

        val state = runTransactionCatching {
            // Balance after the stake is taken — stable for the whole round, so
            // `open` can reuse it without another operator call.
            val balance = playerService.findBalance(player, sessionToken, currency).getOrThrow()
            val info = MinesState.PlayerInfo.from(player, balance)

            val game = MinesGame.new {
                this.round = round
                this.mineCount = command.mineCount
                this.placeAmount = Amount(command.amount)
                this.balance = balance
                this.opened = ""
                this.serverSeed = provablyFair.generateServerSeed()
                this.clientSeed = connection.requireClientSeed()
                this.tonSeed = provablyFair.getTonSeed()
            }

            MinesState.active(game, info)
        }.getOrElse { e ->
            // The operator took the stake but the game could not start: refund + void.
            roundService.compensateDebit(player, sessionToken, MinesGame.GAME, currency, debit)
            throw e
        }

        connection.send(state)
    }
}
