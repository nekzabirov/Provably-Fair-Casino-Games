package game.pvp.command

import game.pvp.network.Connection
import game.pvp.service.PvpEngine
import kotlinx.serialization.Serializable

/** bet — place or top up this player's stake for the current round; currency comes from the connection. */
@Serializable
data class PvpBet(val amount: Long)

class PvpBetHandler(private val engine: PvpEngine) {
    suspend fun handle(connection: Connection, command: PvpBet) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val token = connection.requireToken()

        val state = engine.bet(player, token, currency, command.amount)
        connection.send(state)
    }
}
