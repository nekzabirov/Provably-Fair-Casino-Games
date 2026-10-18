package game.telefly.command

import game.telefly.network.Connection
import game.telefly.service.TeleFlyEngine
import kotlinx.serialization.Serializable

/** cashout — credit current winnings at the live multiplier and close the round. No payload. */
@Serializable
class TeleFlyCashout

class TeleFlyCashoutHandler(private val engine: TeleFlyEngine) {
    suspend fun handle(connection: Connection, command: TeleFlyCashout) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val token = connection.requireToken()

        val state = engine.cashout(player, token, currency)
        connection.send(state)
    }
}
