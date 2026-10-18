package game.lottery.command

import game.lottery.network.Connection
import game.lottery.network.ConnectionRegistry
import game.lottery.service.LotteryEngine
import kotlinx.serialization.Serializable
import service.PlayerService

/**
 * auth — resolve the player from (token, operator), register the connection, and
 * return the current lottery snapshot. The lottery is TON-only, so the currency is
 * fixed regardless of any client-sent value.
 */
@Serializable
data class LotteryAuth(val token: String, val operator: String, val currency: String? = null)

class LotteryAuthHandler(
    private val playerService: PlayerService,
    private val registry: ConnectionRegistry,
    private val engine: LotteryEngine
) {
    suspend fun handle(connection: Connection, command: LotteryAuth) {
        val player = playerService.findByToken(command.token, command.operator).getOrThrow()
        registry.register(player.id.value, connection)
        connection.authenticate(player, LotteryEngine.CURRENCY, command.token)

        val state = engine.snapshotFor(player)
        connection.send(state)
    }
}
