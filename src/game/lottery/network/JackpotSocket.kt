package game.lottery.network

import game.lottery.service.LotteryEngine
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import org.koin.ktor.ext.inject

/**
 * The `/ws/jackpot` route — public and un-authenticated. On connect we send the current
 * jackpot frame immediately, then the engine pushes a fresh frame on every change. The
 * client sends nothing; we just hold the socket open (Ktor server pings keep it alive).
 */
fun Application.jackpotSocket() {
    val registry by inject<JackpotRegistry>()
    val engine by inject<LotteryEngine>()

    routing {
        webSocket("/ws/jackpot") {
            registry.add(this)
            try {
                engine.currentJackpot()?.let { send(Frame.Text(it)) }
                // Drain incoming (ignored) until the peer closes; keeps the socket open.
                for (frame in incoming) { /* no client commands on the jackpot channel */ }
            } finally {
                registry.remove(this)
            }
        }
    }
}
