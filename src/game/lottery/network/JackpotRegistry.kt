package game.lottery.network

import io.ktor.websocket.Frame
import io.ktor.server.websocket.DefaultWebSocketServerSession
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import util.Env

/**
 * Live `/ws/jackpot` listeners. The jackpot frame is identical for everyone (no
 * personalization, no auth), so a single rendered string is fanned out to all sockets.
 * The engine pushes on every shared change (a sale, a reveal, a settle, a new week);
 * a new socket gets the current frame on connect. Listener count is unbounded but the
 * frame rate is low, and each send is isolated (a dead client is dropped after a timeout).
 */
class JackpotRegistry {

    private val listeners = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    fun add(session: DefaultWebSocketServerSession) {
        listeners.add(session)
    }

    fun remove(session: DefaultWebSocketServerSession) {
        listeners.remove(session)
    }

    /** Fan the (already-serialized) jackpot frame out to every listener. */
    suspend fun broadcast(text: String) {
        for (session in listeners) {
            try {
                withTimeoutOrNull(Env.lotterySendTimeout) { session.send(Frame.Text(text)) }
            } catch (e: Exception) {
                log.debug("jackpot broadcast send failed; dropping one listener frame", e)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(JackpotRegistry::class.java)
    }
}
