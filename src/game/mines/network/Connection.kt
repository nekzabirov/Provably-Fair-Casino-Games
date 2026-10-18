package game.mines.network

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
 * and read by every subsequent command (currency lives on the connection, not on
 * each frame). [send] serializes any @Serializable payload to a text frame.
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

    /** The player's provably-fair client seed, supplied on auth and used by the next round. */
    var clientSeed: String? = null
        private set

    fun authenticate(player: Player, currency: Currency, token: String, clientSeed: String) {
        this.player = player
        this.currency = currency
        this.token = token
        this.clientSeed = clientSeed
    }

    fun requirePlayer(): Player = player ?: throw NotAuthenticatedException()

    fun requireCurrency(): Currency = currency ?: throw NotAuthenticatedException()

    fun requireToken(): String = token ?: throw NotAuthenticatedException()

    fun requireClientSeed(): String = clientSeed ?: throw NotAuthenticatedException()

    suspend inline fun <reified T> send(payload: T) {
        session.send(Frame.Text(json.encodeToString(payload)))
    }

    suspend fun close(reason: String) {
        session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
    }
}
