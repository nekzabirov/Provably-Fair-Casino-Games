package game.shell.network

import java.util.concurrent.ConcurrentHashMap

/**
 * One live connection per player. Registering a player who already has a
 * connection closes the previous socket (replace-on-reauth).
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
}
