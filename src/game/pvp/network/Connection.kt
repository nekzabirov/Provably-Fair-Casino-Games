package game.pvp.network

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.Player
import util.Currency

/**
 * One WebSocket connection. Holds the player and currency, both set once on auth
 * and read by every subsequent command. [send] serializes a @Serializable payload
 * for personal replies; [sendText] writes an already-serialized frame (used by the
 * engine's broadcast, which serializes once and fans out to all connections).
 */
class Connection(
    @PublishedApi internal val session: DefaultWebSocketServerSession,
    @PublishedApi internal val json: Json
) {
    var player: Player? = null
        private set

    var currency: Currency? = null
        private set

    var token: String? = null
        private set

    fun authenticate(player: Player, currency: Currency, token: String) {
        this.player = player
        this.currency = currency
        this.token = token
    }

    fun requirePlayer(): Player = player ?: throw NotAuthenticatedException()

    fun requireCurrency(): Currency = currency ?: throw NotAuthenticatedException()

    fun requireToken(): String = token ?: throw NotAuthenticatedException()

    suspend inline fun <reified T> send(payload: T) {
        session.send(Frame.Text(json.encodeToString(payload)))
    }

    /** Send a pre-serialized frame (broadcast path — no per-connection encoding). */
    suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    suspend fun close(reason: String) {
        session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
    }
}
