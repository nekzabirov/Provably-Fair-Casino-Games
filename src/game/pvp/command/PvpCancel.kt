package game.pvp.command

import game.pvp.network.Connection
import game.pvp.service.PvpEngine
import kotlinx.serialization.Serializable

/** cancel — refund this player's whole stake and leave the round. No payload. Only before lock. */
@Serializable
class PvpCancel

class PvpCancelHandler(private val engine: PvpEngine) {
    suspend fun handle(connection: Connection, command: PvpCancel) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val token = connection.requireToken()

        val state = engine.cancel(player, token, currency)
        connection.send(state)
    }
}
