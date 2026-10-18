package game.telefly.network

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import util.Env

/**
 * Live connections for TeleFly. Unlike the per-player games, TeleFly is shared:
 * the engine [broadcast]s phase/tick/crash/feed frames to everyone. One connection
 * per player (replace-on-reauth); the engine's bet state is keyed separately by
 * playerId, so a disconnect does not drop an in-flight bet.
 */
class ConnectionRegistry {

    private val connections = ConcurrentHashMap<Long, Connection>()

    suspend fun register(playerId: Long, connection: Connection) {
        connections.put(playerId, connection)?.close("Replaced by a new connection")
    }

    /** Removes the mapping only if [connection] is still the registered one. */
    fun unregister(playerId: Long, connection: Connection) {
        connections.remove(playerId, connection)
    }

    /**
     * Fan out one already-serialized frame to every connection. Each send is
     * isolated (a slow/dead client is dropped after a timeout) so one bad socket
     * can never stall the tick loop or the other clients.
     */
    suspend fun broadcast(text: String) {
        for (connection in connections.values) {
            try {
                withTimeoutOrNull(Env.teleFlySendTimeout) { connection.sendText(text) }
            } catch (e: Exception) {
                log.debug("broadcast send failed; dropping frame for one connection", e)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)
    }
}
