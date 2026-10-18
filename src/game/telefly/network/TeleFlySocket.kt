package game.telefly.network

import game.telefly.command.TeleFlyAuth
import game.telefly.command.TeleFlyAuthHandler
import game.telefly.command.TeleFlyBet
import game.telefly.command.TeleFlyBetHandler
import game.telefly.command.TeleFlyCashout
import game.telefly.command.TeleFlyCashoutHandler
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

/**
 * The /ws/telefly route. The crash round is global and always running (driven by
 * TeleFlyEngine); a connection only authenticates, places one bet, and cashes out.
 * The engine pushes phase/tick/crash/feed frames to all connections. The active
 * bet is restored on (re)connect via the `auth` reply; it survives a disconnect
 * because the engine keys bets by playerId, not by connection.
 */
fun Application.teleFlySocket() {
    val registry by inject<ConnectionRegistry>()
    val json by inject<Json>()
    val authHandler by inject<TeleFlyAuthHandler>()
    val betHandler by inject<TeleFlyBetHandler>()
    val cashoutHandler by inject<TeleFlyCashoutHandler>()

    val bus = CommandBus(json).apply {
        register<TeleFlyAuth>("auth") { connection, command -> authHandler.handle(connection, command) }
        register<TeleFlyBet>("bet") { connection, command -> betHandler.handle(connection, command) }
        register<TeleFlyCashout>("cashout") { connection, command -> cashoutHandler.handle(connection, command) }
    }

    routing {
        webSocket("/ws/telefly") {
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
