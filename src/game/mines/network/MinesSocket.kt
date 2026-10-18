package game.mines.network

import game.mines.command.MinesAuth
import game.mines.command.MinesAuthHandler
import game.mines.command.MinesCashout
import game.mines.command.MinesCashoutHandler
import game.mines.command.MinesOpen
import game.mines.command.MinesOpenHandler
import game.mines.command.MinesStart
import game.mines.command.MinesStartHandler
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

/**
 * The /ws/mines route. One connection per player; the active round is restored on
 * (re)connect via the `auth` command. Frames are dispatched by `type` through the
 * command bus; the connection is unregistered on disconnect.
 */
fun Application.minesSocket() {
    val registry by inject<ConnectionRegistry>()
    val json by inject<Json>()
    val authHandler by inject<MinesAuthHandler>()
    val startHandler by inject<MinesStartHandler>()
    val openHandler by inject<MinesOpenHandler>()
    val cashoutHandler by inject<MinesCashoutHandler>()

    val bus = CommandBus(json).apply {
        register<MinesAuth>("auth") { connection, command -> authHandler.handle(connection, command) }
        register<MinesStart>("start") { connection, command -> startHandler.handle(connection, command) }
        register<MinesOpen>("open") { connection, command -> openHandler.handle(connection, command) }
        register<MinesCashout>("cashout") { connection, command -> cashoutHandler.handle(connection, command) }
    }

    routing {
        webSocket("/ws/mines") {
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
