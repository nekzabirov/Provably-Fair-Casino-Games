package game.telefly.command

import game.telefly.network.Connection
import game.telefly.service.TeleFlyEngine
import kotlinx.serialization.Serializable

/** bet — place the single bet for the current round; currency comes from the connection. */
@Serializable
data class TeleFlyBet(val amount: Long)

class TeleFlyBetHandler(private val engine: TeleFlyEngine) {
    suspend fun handle(connection: Connection, command: TeleFlyBet) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val token = connection.requireToken()

        val state = engine.placeBet(player, token, currency, command.amount)
        connection.send(state)
    }
}
