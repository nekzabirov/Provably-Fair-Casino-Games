package game.pvp.command

import game.pvp.network.Connection
import game.pvp.network.ConnectionRegistry
import game.pvp.service.PvpEngine
import kotlinx.serialization.Serializable
import service.PlayerService
import util.Currency

/**
 * auth — resolve player from (token, operator), store player + currency (always
 * TON), register, return live state. Any client-sent `currency` is ignored; PvP is
 * played in TON only.
 */
@Serializable
data class PvpAuth(val token: String, val operator: String, val currency: String? = null)

class PvpAuthHandler(
    private val playerService: PlayerService,
    private val registry: ConnectionRegistry,
    private val engine: PvpEngine
) {
    suspend fun handle(connection: Connection, command: PvpAuth) {
        val player = playerService.findByToken(command.token, command.operator).getOrThrow()
        val currency = Currency.TON
        registry.register(player.id.value, connection)
        connection.authenticate(player, currency, command.token)

        val state = engine.snapshotFor(player, command.token, currency)
        connection.send(state)
    }
}
