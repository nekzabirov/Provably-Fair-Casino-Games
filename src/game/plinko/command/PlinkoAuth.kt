package game.plinko.command

import game.plinko.model.PlinkoState
import game.plinko.network.Connection
import game.plinko.network.ConnectionRegistry
import kotlinx.serialization.Serializable
import service.PlayerService
import service.ProvablyFair
import util.Currency
import util.runTransactionCatching

/**
 * auth — resolve player from (token, operator), store player + currency (always
 * TON) + the player's provably-fair [clientSeed], register, return state. Plinko is
 * instant, so there is never an in-progress round to restore — auth always answers
 * WAIT. Any client-sent `currency` is ignored; the engine plays in TON only.
 */
@Serializable
data class PlinkoAuth(
    val token: String,
    val operator: String,
    val currency: String? = null,
    val clientSeed: String = ""
)

class PlinkoAuthHandler(
    private val playerService: PlayerService,
    private val provablyFair: ProvablyFair,
    private val registry: ConnectionRegistry
) {
    suspend fun handle(connection: Connection, command: PlinkoAuth) {
        val player = playerService.findByToken(command.token, command.operator).getOrThrow()
        val currency = Currency.TON
        val clientSeed = provablyFair.sanitizeClientSeed(command.clientSeed)
        registry.register(player.id.value, connection)
        connection.authenticate(player, currency, command.token, clientSeed)

        val state = runTransactionCatching {
            val balance = playerService.findBalance(player, command.token, currency).getOrThrow()
            PlinkoState.wait(PlinkoState.PlayerInfo.from(player, balance), clientSeed)
        }.getOrThrow()

        connection.send(state)
    }
}
