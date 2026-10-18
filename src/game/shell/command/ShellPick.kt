package game.shell.command

import exception.RoundNotFoundException
import game.shell.model.ShellGame
import game.shell.model.ShellState
import game.shell.network.Connection
import game.shell.service.ShellResult
import kotlinx.serialization.Serializable
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Amount
import util.runTransactionCatching

/** pick — choose a cup. Resolves the round provably-fair and closes it: WIN (×2) or LOSE. */
@Serializable
data class ShellPick(val cup: Int)

class ShellPickHandler(
    private val roundService: RoundService,
    private val provablyFair: ProvablyFair,
    private val playerService: PlayerService
) {
    suspend fun handle(connection: Connection, command: ShellPick) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val sessionToken = connection.requireToken()

        val (game, winningCup, won) = runTransactionCatching {
            require(command.cup in 0 until ShellResult.CUPS) { "cup out of range" }

            val round = roundService.findActiveRound(player, ShellGame.GAME, currency)
                ?: throw RoundNotFoundException()
            val game = ShellGame.forRound(round) ?: throw RoundNotFoundException()

            val hash = provablyFair.computeHash(game.serverSeed, game.clientSeed, game.tonSeed, round.id.value.toString())
            val winningCup = ShellResult.winningCup(hash)
            Triple(game, winningCup, command.cup == winningCup)
        }.getOrThrow()

        val state = if (won) {
            val settle = Amount(game.placeAmount.value * 2)
            // Credit in its own flow: a lost webhook leaves a PENDING row whose id a
            // retried pick replays — the operator deduplicates instead of paying twice.
            roundService.credit(player, sessionToken, ShellGame.GAME, currency, settle).getOrThrow()

            runTransactionCatching {
                roundService.close(player, sessionToken, ShellGame.GAME, currency).getOrThrow()

                // Fetch balance so the client sees the credited payout.
                val balance = playerService.findBalance(player, sessionToken, currency).getOrThrow()
                ShellState.win(game, ShellState.PlayerInfo.from(player, balance), winningCup, settle.value)
            }.getOrThrow()
        } else {
            runTransactionCatching {
                roundService.close(player, sessionToken, ShellGame.GAME, currency).getOrThrow()
                // Balance is unchanged from start (stake already taken); reuse it — no operator call.
                ShellState.lose(game, ShellState.PlayerInfo.from(player, game.balance), winningCup)
            }.getOrThrow()
        }

        connection.send(state)
    }
}
