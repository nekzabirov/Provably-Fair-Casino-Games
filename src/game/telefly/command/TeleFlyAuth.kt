package game.telefly.command

import game.telefly.network.Connection
import game.telefly.network.ConnectionRegistry
import game.telefly.service.TeleFlyEngine
import kotlinx.serialization.Serializable
import service.PlayerService
import util.Currency

/**
 * auth — resolve player from (token, operator), store player + currency (always
 * TON), register, return live state. Any client-sent `currency` is ignored; the
 * engine plays in TON only.
 */
@Serializable
data class TeleFlyAuth(val token: String, val operator: String, val currency: String? = null)

class TeleFlyAuthHandler(
    private val playerService: PlayerService,
    private val registry: ConnectionRegistry,
    private val engine: TeleFlyEngine
) {
    suspend fun handle(connection: Connection, command: TeleFlyAuth) {
        val player = playerService.findByToken(command.token, command.operator).getOrThrow()
        val currency = Currency.TON
        registry.register(player.id.value, connection)
        connection.authenticate(player, currency, command.token)

        val state = engine.snapshotFor(player, command.token, currency)
        connection.send(state)
    }
}
