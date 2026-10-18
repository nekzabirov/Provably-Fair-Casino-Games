package game.shell.command

import game.shell.model.ShellGame
import game.shell.model.ShellState
import game.shell.network.Connection
import kotlinx.serialization.Serializable
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Amount
import util.runTransactionCatching

/** start — open round + debit the stake; currency comes from the connection. */
@Serializable
data class ShellStart(val amount: Long)

class ShellStartHandler(
    private val roundService: RoundService,
    private val provablyFair: ProvablyFair,
    private val playerService: PlayerService
) {
    suspend fun handle(connection: Connection, command: ShellStart) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val sessionToken = connection.requireToken()

        // The round commits BEFORE the stake moves: the debit runs in its own
        // transactions and must reference a durable round.
        val (round, resumed) = runTransactionCatching {
            require(command.amount > 0) { "amount must be positive" }

            val round = roundService.open(player, sessionToken, ShellGame.GAME, currency).getOrThrow()

            // Idempotent: a re-sent `start` (double click) returns the in-progress
            // round's state WITHOUT debiting again, instead of double-charging.
            val resumed = ShellGame.forRound(round)?.let { existing ->
                val current = playerService.findBalance(player, sessionToken, currency).getOrThrow()
                ShellState.active(existing, ShellState.PlayerInfo.from(player, current))
            }
            round to resumed
        }.getOrThrow()

        if (resumed != null) {
            connection.send(resumed)
            return
        }

        val debit = roundService.debit(player, sessionToken, ShellGame.GAME, currency, Amount(command.amount)).getOrThrow()

        val state = runTransactionCatching {
            // Balance after the stake is taken — stable for the round.
            val balance = playerService.findBalance(player, sessionToken, currency).getOrThrow()
            val info = ShellState.PlayerInfo.from(player, balance)

            val game = ShellGame.new {
                this.round = round
                this.placeAmount = Amount(command.amount)
                this.balance = balance
                this.serverSeed = provablyFair.generateServerSeed()
                this.clientSeed = connection.requireClientSeed()
                this.tonSeed = provablyFair.getTonSeed()
            }

            ShellState.active(game, info)
        }.getOrElse { e ->
            // The operator took the stake but the game could not start: refund + void.
            roundService.compensateDebit(player, sessionToken, ShellGame.GAME, currency, debit)
            throw e
        }

        connection.send(state)
    }
}
