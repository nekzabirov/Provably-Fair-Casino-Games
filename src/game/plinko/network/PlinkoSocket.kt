package game.plinko.network

import game.plinko.command.PlinkoAuth
import game.plinko.command.PlinkoAuthHandler
import game.plinko.command.PlinkoDrop
import game.plinko.command.PlinkoDropHandler
import game.plinko.model.PlinkoGame
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import service.RoundService

/**
 * The /ws/plinko route. One connection per player. Frames are dispatched by `type`
 * through the command bus; commands on one socket are handled sequentially, so
 * rapid-fire drops queue naturally — many drops join one shared open round, which
 * is finalized here when the live connection goes away.
 */
fun Application.plinkoSocket() {
    val registry by inject<ConnectionRegistry>()
    val json by inject<Json>()
    val authHandler by inject<PlinkoAuthHandler>()
    val dropHandler by inject<PlinkoDropHandler>()
    val roundService by inject<RoundService>()

    val bus = CommandBus(json).apply {
        register<PlinkoAuth>("auth") { connection, command -> authHandler.handle(connection, command) }
        register<PlinkoDrop>("drop") { connection, command -> dropHandler.handle(connection, command) }
    }

    routing {
        webSocket("/ws/plinko") {
            val connection = Connection(this, json)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        bus.dispatch(connection, frame.readText())
                    }
                }
            } finally {
                connection.player?.let { player ->
                    // The multi-drop round lives as long as the connection: when the LIVE
                    // connection goes away, finalize it. A connection replaced by a reauth
                    // must not close the round its replacement keeps playing.
                    if (registry.unregister(player.id.value, connection)) {
                        val currency = connection.currency
                        val token = connection.token
                        if (currency != null && token != null) {
                            roundService.close(player, token, PlinkoGame.GAME, currency)
                        }
                    }
                }
            }
        }
    }
}
