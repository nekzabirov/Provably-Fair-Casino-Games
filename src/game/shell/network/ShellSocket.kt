package game.shell.network

import game.shell.command.ShellAuth
import game.shell.command.ShellAuthHandler
import game.shell.command.ShellPick
import game.shell.command.ShellPickHandler
import game.shell.command.ShellStart
import game.shell.command.ShellStartHandler
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

/**
 * The /ws/shell route. One connection per player; the active round is restored on
 * (re)connect via the `auth` command. Frames are dispatched by `type` through the
 * command bus; the connection is unregistered on disconnect.
 */
fun Application.shellSocket() {
    val registry by inject<ConnectionRegistry>()
    val json by inject<Json>()
    val authHandler by inject<ShellAuthHandler>()
    val startHandler by inject<ShellStartHandler>()
    val pickHandler by inject<ShellPickHandler>()

    val bus = CommandBus(json).apply {
        register<ShellAuth>("auth") { connection, command -> authHandler.handle(connection, command) }
        register<ShellStart>("start") { connection, command -> startHandler.handle(connection, command) }
        register<ShellPick>("pick") { connection, command -> pickHandler.handle(connection, command) }
    }

    routing {
        webSocket("/ws/shell") {
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
