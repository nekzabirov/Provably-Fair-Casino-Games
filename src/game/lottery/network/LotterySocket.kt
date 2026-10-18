package game.lottery.network

import game.lottery.command.LotteryAuth
import game.lottery.command.LotteryAuthHandler
import game.lottery.command.LotteryBuy
import game.lottery.command.LotteryBuyHandler
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

/**
 * The /ws/lottery route. The weekly draw is global, always running and shared by all
 * brands (driven by LotteryEngine). A connection authenticates, then may buy one
 * ticket while sales are open; the engine pushes the full lottery snapshot on every
 * change. No frontend of ours — brands integrate purely over this socket.
 */
fun Application.lotterySocket() {
    val registry by inject<ConnectionRegistry>()
    val json by inject<Json>()
    val authHandler by inject<LotteryAuthHandler>()
    val buyHandler by inject<LotteryBuyHandler>()

    val bus = CommandBus(json).apply {
        register<LotteryAuth>("auth") { connection, command -> authHandler.handle(connection, command) }
        register<LotteryBuy>("buy") { connection, command -> buyHandler.handle(connection, command) }
    }

    routing {
        webSocket("/ws/lottery") {
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
