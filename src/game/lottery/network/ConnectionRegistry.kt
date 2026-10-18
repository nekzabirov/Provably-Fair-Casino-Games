package game.lottery.network

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import util.Env

/**
 * Live connections for the lottery. The draw is shared, but each player's snapshot
 * carries their own ticket, so the engine fans out a PERSONALIZED frame per player
 * via [broadcastPersonalized] (the lottery's event rate is low — sparse purchases,
 * six reveals, one settle — so per-connection serialization is cheap, unlike the
 * 100ms tick games). One connection per player (replace-on-reauth).
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
     * Render and send a per-player frame to every connection. [render] is a pure
     * function of the playerId over an immutable snapshot captured by the caller, so
     * no shared state is touched here. Each send is isolated (a slow/dead client is
     * dropped after a timeout) so one bad socket never stalls the draw loop.
     */
    suspend fun broadcastPersonalized(render: (Long) -> String) {
        for ((playerId, connection) in connections) {
            try {
                withTimeoutOrNull(Env.lotterySendTimeout) { connection.sendText(render(playerId)) }
            } catch (e: Exception) {
                log.debug("broadcast send failed; dropping frame for one connection", e)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)
    }
}
