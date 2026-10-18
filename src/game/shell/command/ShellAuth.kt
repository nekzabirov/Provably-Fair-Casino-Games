package game.shell.command

import game.shell.model.ShellGame
import game.shell.model.ShellState
import game.shell.network.Connection
import game.shell.network.ConnectionRegistry
import kotlinx.serialization.Serializable
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Currency
import util.runTransactionCatching

/**
 * auth — resolve player from (token, operator), store player + currency (always
 * TON) + the player's provably-fair [clientSeed], register, return state. Any
 * client-sent `currency` is ignored; the engine plays in TON only.
 */
@Serializable
data class ShellAuth(
    val token: String,
    val operator: String,
    val currency: String? = null,
    val clientSeed: String = ""
)

class ShellAuthHandler(
    private val playerService: PlayerService,
    private val roundService: RoundService,
    private val provablyFair: ProvablyFair,
    private val registry: ConnectionRegistry
) {
    suspend fun handle(connection: Connection, command: ShellAuth) {
        val player = playerService.findByToken(command.token, command.operator).getOrThrow()
        val currency = Currency.TON
        val clientSeed = provablyFair.sanitizeClientSeed(command.clientSeed)
        registry.register(player.id.value, connection)
        connection.authenticate(player, currency, command.token, clientSeed)

        val state = runTransactionCatching {
            val balance = playerService.findBalance(player, command.token, currency).getOrThrow()
            val info = ShellState.PlayerInfo.from(player, balance)

            val game = roundService.findActiveRound(player, ShellGame.GAME, currency)?.let(ShellGame::forRound)
            if (game == null) ShellState.wait(info, clientSeed) else ShellState.active(game, info)
        }.getOrThrow()

        connection.send(state)
    }
}
