package game.pvp.network

import game.pvp.command.PvpAuth
import game.pvp.command.PvpAuthHandler
import game.pvp.command.PvpBet
import game.pvp.command.PvpBetHandler
import game.pvp.command.PvpCancel
import game.pvp.command.PvpCancelHandler
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

/**
 * The /ws/pvp route. The jackpot round is global and always running (driven by
 * PvpEngine); a connection authenticates, then bets/tops-up/cancels before lock.
 * The engine pushes phase/spin frames to all connections. The player's stake is
 * restored on (re)connect via the `auth` reply; it survives a disconnect because
 * the engine keys bets by playerId, not by connection.
 */
fun Application.pvpSocket() {
    val registry by inject<ConnectionRegistry>()
    val json by inject<Json>()
    val authHandler by inject<PvpAuthHandler>()
    val betHandler by inject<PvpBetHandler>()
    val cancelHandler by inject<PvpCancelHandler>()

    val bus = CommandBus(json).apply {
        register<PvpAuth>("auth") { connection, command -> authHandler.handle(connection, command) }
        register<PvpBet>("bet") { connection, command -> betHandler.handle(connection, command) }
        register<PvpCancel>("cancel") { connection, command -> cancelHandler.handle(connection, command) }
    }

    routing {
        webSocket("/ws/pvp") {
            val connection = Connection(this, json)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        bus.dispatch(connection, frame.readText())
                    }
                }
            } finally {
                connection.player?.let { registry.unregister(it.id.value, connection) }
            }
        }
    }
}
